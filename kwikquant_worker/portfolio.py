"""组合(多标的)回测事件循环 + 策略 ctx。

与单标的 ``event_loop.BacktestEventLoop`` / ``strategy.BacktestContext`` 并存:
单标的策略写顶层 ``def on_bar(bar, ctx)``,组合策略写顶层 ``def on_bars(ctx)``。
ctx 契约与单标的/runner 统一(``context.StrategyContext`` Protocol,docs/strategy-api.md),
差异仅:组合无单一交易对(``symbol`` 返空串),``place_order/history/position/close_position``
必须显式传 ``symbol``(限本任务 symbols() 内)。

组合契约 ``on_bars(ctx)`` 的 ctx 提供:

- ``params``:任务 parameters(只读 Mapping;模块级 ``PARAMS`` 同源);
- ``symbols()``:本任务配置的标的列表;
- ``bar(symbol)``:该标的当前时间轴步已收盘的 bar(该标本步缺 bar 返 ``None``);
- ``history(field, n, symbol=...)``:该标的最近 n 根已收盘 K 线的 field 值(**只到当前已收盘
  bar,严禁未来数据**——指针只推进到 ``<= 当前时间轴`` 的 bar);
- ``position(symbol=...)``:该标的账本持仓(qty/avg_price);
- ``equity()`` / ``available_cash()``:**直接读引擎内部真实账本**(同一对象引用,非拷贝),
  策略无需也不应自维护现金账本;
- ``place_order(symbol=..., side=..., order_type=..., amount=...)`` -> ``OrderAck``:排队至
  该标的**下一根可用 bar** 撮合(NEXT_BAR 语义);
- ``close_position(symbol=...)`` -> ``OrderAck``:市价全平(账本原值下单)。

撮合定价、费率、滑点与单标的回测**完全一致**:复用同一 ``backtest/matching.match`` 与同一
``MatchConfig``(``docs/matching-spec.md``)。现金为全组合共享,逐时间轴步对全组合
mark-to-market 记权益。金额红线:内部账本全 ``Decimal``,行情 OHLC 给用户 ``float``,
下单 amount/price 拒 float(``context.normalize_order`` 单源校验)。
"""

from __future__ import annotations

import logging
import sys
from dataclasses import replace
from decimal import Decimal
from types import MappingProxyType
from typing import Any, Mapping

from kwikquant_worker import acceptance
from kwikquant_worker.backtest import matching
from kwikquant_worker.backtest.matching import MatchConfig, OrderIntent
from kwikquant_worker.backtest.perp_ledger import (
    FundingPeriod,
    FundingReplay,
    PerpPortfolioLedger,
    PerpPosition,
    norm,
)
from kwikquant_worker.context import (
    PREDICTED_FUNDING_RUNNER_ONLY,
    FillEvent,
    FundingEvent,
    LiquidationEvent,
    OrderAck,
    clamp_dust_close,
    normalize_order,
)
from kwikquant_worker.event_loop import (
    PROGRESS_REPORT_EVERY,
    _bar_from_kline,
    _dispatch_callback,
    _reject_slots_left,
    _TradeRecord,
)
from kwikquant_worker.strategy import Bar, Fill, Position

log = logging.getLogger(__name__)


class PortfolioContext:
    """组合回测策略上下文。由 :class:`PortfolioEventLoop` 驱动,逐时间轴步刷新指针。

    账本(现金/持仓)由引擎持有,ctx 经 ``_loop`` 反查真实账本——``equity()`` /
    ``available_cash()`` 读的是引擎内部值,策略自维护现金账本既无必要也不被鼓励。
    """

    def __init__(
        self,
        client,
        task_id: int,
        *,
        exchange: str = "BINANCE",
        market_type: str = "SPOT",
        symbols: list[str] | None = None,
        params: Mapping[str, Any] | None = None,
    ) -> None:
        self._client = client
        self._task_id = task_id
        self._exchange = exchange
        self._market_type = market_type
        self._symbols = list(symbols or [])
        # 浅冻结:与 module.PARAMS 同一语义(不可增删键)
        self._params: Mapping[str, Any] = MappingProxyType(dict(params or {}))
        self._series: dict[str, list[dict]] = {}
        self._ptr: dict[str, int] = {}
        self._current_ts: str | None = None
        self._pending: list[OrderIntent] = []
        self._loop: "PortfolioEventLoop | None" = None

    # ---------- 引擎侧装配(非策略 API) ----------

    def bind(self, loop: "PortfolioEventLoop") -> None:
        self._loop = loop

    def set_series(self, series: dict[str, list[dict]]) -> None:
        self._series = series

    def set_index(self, ts: str, ptr: dict[str, int]) -> None:
        self._current_ts = ts
        self._ptr = ptr

    # ---------- 策略 API ----------

    @property
    def params(self) -> Mapping[str, Any]:
        return self._params

    def symbols(self) -> list[str]:
        """本任务配置的标的列表(副本,防策略改动内部状态)。"""
        return list(self._symbols)

    @property
    def symbol(self) -> str:
        """组合无单一交易对;返回空串以兼容可能误用的单标的代码路径。"""
        return ""

    @staticmethod
    def _require_symbol(symbol: str | None) -> str:
        """组合 ctx 的 symbol 必填(无单一绑定交易对,缺省即契约违规,fail-closed)。"""
        if symbol is None:
            raise ValueError("组合 ctx 无单一交易对:必须显式传 symbol(限 symbols() 内)")
        return symbol

    def bar(self, symbol: str) -> Bar | None:
        """该标的**当前时间轴步**已收盘的 bar;该标本步缺 bar(或未开场)返 ``None``。"""
        ptr = self._ptr.get(symbol, 0)
        series = self._series.get(symbol)
        if not series or ptr <= 0:
            return None
        last = series[ptr - 1]
        if str(last["timestamp"]) != self._current_ts:
            return None
        return _bar_from_kline({**last, "openTime": last["timestamp"]})

    def history(self, field: str, n: int, symbol: str | None = None) -> list[float]:
        """该标的最近 n 根(含当前已收盘 bar)K 线的 field 值,``list[float]``。

        签名与单标的/runner ctx 统一(``symbol`` 组合必传,keyword)。
        **只到当前已收盘 bar**——指针 ``_ptr[symbol]`` 仅覆盖 ``timestamp <= 当前时间轴`` 的
        bar,未来数据不可见。不足 n 根(开头 warmup)返已有;该标尚无 bar 返 ``[]``。
        """
        sym = self._require_symbol(symbol)
        series = self._series.get(sym)
        ptr = self._ptr.get(sym, 0)
        if not series or ptr <= 0 or n <= 0:
            return []
        start = max(0, ptr - n)
        return [float(str(k[field])) for k in series[start:ptr]]

    def position(self, symbol: str | None = None) -> Position:
        sym = self._require_symbol(symbol)
        if self._loop is None:
            return Position(symbol=sym, qty=Decimal(0), avg_price=Decimal(0))
        return self._loop.position(sym)

    def equity(self) -> Decimal:
        """组合权益 = 现金 + Σ(持仓 × 该标最新已收盘 close)。直接读引擎账本。"""
        if self._loop is None:
            return Decimal(0)
        return self._loop.current_equity()

    def available_cash(self) -> Decimal:
        """全组合共享的可用现金(PERP = cash − Σ_iso_margin)。直接读引擎账本。"""
        if self._loop is None:
            return Decimal(0)
        return self._loop.available_cash()

    def place_order(
        self,
        *,
        symbol: str | None = None,
        side: str | None = None,
        order_type: str,
        amount: Decimal | int | str,
        price: Decimal | int | str | None = None,
        position_effect: str | None = None,
        leverage: int | None = None,
        margin_mode: str | None = None,
    ) -> OrderAck:
        """组合下单:校验后入 ``_pending`` 队列,引擎在该标的**下一根可用 bar** 本地撮合。

        签名与单标的/runner ctx 统一(``symbol`` 组合必传,keyword)。校验 fail-closed
        (抛 ``ValueError``/``TypeError``),共享单源 ``context.normalize_order``:symbol 必须在
        本任务标的列表内;SPOT side ∈ BUY/SELL;PERP position_effect 必填四向、side 派生、
        leverage/marginMode 开首仓必填(docs/perp-backtest-spec.md §10);amount/price **拒 float** 且 > 0。
        返 ``OrderAck(accepted=True)`` = 已排队(NEXT_BAR 成交,拒单异步进 warnings)。
        """
        sym = self._require_symbol(symbol)
        if sym not in self._symbols:
            raise ValueError(
                f"place_order symbol 非法: {sym!r}(不在本任务标的列表 {self._symbols})"
            )
        o = normalize_order(
            market_type=self._market_type,
            side=side,
            order_type=order_type,
            amount=amount,
            price=price,
            position_effect=position_effect,
            leverage=leverage,
            margin_mode=margin_mode,
        )
        self._pending.append(
            OrderIntent(
                symbol=sym,
                side=o.side,
                order_type=o.order_type,
                amount=o.amount,
                price=o.price,
                position_effect=o.position_effect,
                leverage=o.leverage,
                margin_mode=o.margin_mode,
            )
        )
        return OrderAck(accepted=True)

    def close_position(self, symbol: str | None = None) -> OrderAck:
        """市价全平该标的持仓(账本原值下单,绕开精度残差)。无持仓返
        ``OrderAck(accepted=False, reason="NO_POSITION")``。排队语义同 place_order。

        PERP 按 signed 净持仓派生 CLOSE_LONG/CLOSE_SHORT(全平量 = |signed_qty|);SPOT SELL 全量。
        """
        sym = self._require_symbol(symbol)
        pos = self.position(sym)
        if pos.qty == 0:
            return OrderAck(accepted=False, reason="NO_POSITION")
        if self._market_type == "PERP":
            effect = "CLOSE_LONG" if pos.qty > 0 else "CLOSE_SHORT"
            return self.place_order(symbol=sym, order_type="MARKET", amount=abs(pos.qty), position_effect=effect)
        return self.place_order(symbol=sym, side="SELL", order_type="MARKET", amount=pos.qty)

    def take_pending(self) -> list[OrderIntent]:
        intents, self._pending = self._pending, []
        return intents

    def cancel(self, order_id: int) -> None:
        """回测 no-op:与单标的 ``BacktestContext.cancel`` 同语义(限价单单根 bar 自动过期)。"""
        return None

    def log(self, msg: str) -> None:
        print(f"[strategy] {msg}", file=sys.stderr)

    def report_progress(self, processed: int, total: int) -> None:
        try:
            self._client.trade.report_progress(self._task_id, processed, total)
        except Exception as e:  # noqa: BLE001 — 进度上报失败不阻断回测
            print(f"[ctx] report_progress failed: {e!r}", file=sys.stderr)

    def predicted_funding_rate(self, symbol: str | None = None) -> Decimal | None:
        """组合回测无预估资金费:抛 NotImplementedError(运行时能力分叉,docs/strategy-api.md §9)。"""
        raise NotImplementedError(PREDICTED_FUNDING_RUNNER_ONLY)


class PortfolioEventLoop:
    """组合回测事件循环:多标的按公共时间轴对齐推进,共享现金池,逐根 mark-to-market。

    与 :class:`BacktestEventLoop` 对偶,差异仅在"一策略多标的 + 共享资金池";撮合内核、
    MatchConfig、NEXT_BAR 时序、账本闸门语义完全一致。
    """

    def __init__(
        self,
        *,
        initial_capital: Decimal = Decimal("100000"),
        symbols: list[str] | None = None,
        timeframe: str = "",
        params: dict[str, Any] | None = None,
        reproducibility: dict[str, Any] | None = None,
        matching_config: dict[str, Any] | None = None,
        market_type: str = "SPOT",
        funding_periods: dict[str, list[FundingPeriod]] | None = None,
        task_end: str | None = None,
        pair_specs: dict[str, dict] | None = None,
    ) -> None:
        self.initial_capital = initial_capital
        self.symbols = list(symbols or [])
        self.timeframe = timeframe
        self.params = params or {}
        self.reproducibility = reproducibility or {}
        self.match_config = MatchConfig.from_dict(matching_config)
        self.market_type = market_type
        # 接受性快照(symbol → Java 下发 camelCase dict)。仅组合 PERP 路径消费(§10.6):
        # 撮合前逐 symbol 过 acceptance(leverage 值域/maxLeverage/minQty/stepSize/tickSize),
        # 与单标的 PERP 同源。SPOT 组合不消费(存量行为,复现不变)。
        self._pair_specs = pair_specs or {}
        # PERP 组合账户账本(共享现金 + per-symbol 净持仓);SPOT 走下方 cash/positions
        self._perp: PerpPortfolioLedger | None = None
        self._funding_periods = funding_periods or {}
        self._task_end = task_end
        # 引擎内部真实账本(全组合共享现金 + 分标的持仓)——SPOT 用
        self.cash: Decimal = initial_capital
        self.positions: dict[str, Position] = {}
        # mark-to-market 所需的最新已收盘 close(逐时间轴步刷新)
        self._last_close: dict[str, Decimal] = {}
        self._series: dict[str, list[dict]] = {}
        self._ptr: dict[str, int] = {}

    # ---------- 账本读取(策略 ctx 经此读真实账本) ----------

    def position(self, symbol: str) -> Position:
        # 返回副本:策略代码不可信,不能把引擎账本的可变活引用暴露出去
        # (否则策略改写 qty/avg_price 会静默污染账本,SELL 库存闸门也会被架空)。
        if self._perp is not None:
            p = self._perp.position(symbol)
            if p.signed_qty == 0:
                return Position(symbol=symbol, qty=Decimal(0), avg_price=Decimal(0))
            mark = self._last_close.get(symbol, p.avg_price)
            return Position(
                symbol=symbol,
                qty=p.signed_qty,
                avg_price=p.avg_price,
                leverage=p.leverage,
                margin_mode=p.margin_mode,
                margin=p.margin,
                liquidation_price=self._perp.liquidation_reference_price(symbol),
                unrealized_pnl=self._perp.unrealized(symbol, mark),
            )
        p = self.positions.get(symbol)
        if p is None:
            return Position(symbol=symbol, qty=Decimal(0), avg_price=Decimal(0))
        return Position(symbol=p.symbol, qty=p.qty, avg_price=p.avg_price)

    def available_cash(self) -> Decimal:
        """全组合共享可用现金(PERP = cash − Σ_iso_margin;SPOT = 共享 cash)。"""
        if self._perp is not None:
            return self._perp.available()
        return self.cash

    def current_equity(self) -> Decimal:
        """账户权益。PERP = cash + Σ 持仓 unrealized(各标最新 close);SPOT = cash + Σ(持仓 × close)。"""
        if self._perp is not None:
            return self._perp.equity(self._last_close)
        holdings = Decimal(0)
        for sym, pos in self.positions.items():
            if pos.qty == 0:
                continue
            close = self._last_close.get(sym)
            if close is not None:
                holdings += pos.qty * close
        return self.cash + holdings

    def _apply_fill(self, fill: Fill) -> None:
        """更新分标的持仓均价(与单标的 ``BacktestContext._apply_fill`` 同算法)。"""
        pos = self.positions.get(fill.symbol, Position(fill.symbol, Decimal(0), Decimal(0)))
        signed_qty = fill.qty if fill.side == "BUY" else -fill.qty
        new_qty = pos.qty + signed_qty
        if new_qty == 0:
            avg = Decimal(0)
        elif pos.qty == 0 or (pos.qty > 0) != (new_qty > 0):
            avg = fill.price
        elif (pos.qty > 0) != (signed_qty > 0):
            avg = pos.avg_price
        else:
            avg = (pos.qty * pos.avg_price + signed_qty * fill.price) / new_qty
        self.positions[fill.symbol] = Position(fill.symbol, new_qty, avg)

    # ---------- 主循环 ----------

    @staticmethod
    def _snapshot(k: dict) -> dict:
        """撮合快照:用原始 str 保 Decimal 精度(不绕 float),last=close(FAST 市价单用)。"""
        return {
            "timestamp": str(k["timestamp"]),
            "open": str(k["open"]),
            "high": str(k["high"]),
            "low": str(k["low"]),
            "close": str(k["close"]),
            "last": str(k["close"]),
            "volume": str(k.get("volume", 0)),
        }

    def _timeline(self, series: dict[str, list[dict]]) -> list[str]:
        """公共时间轴 = 所有标的 timestamp 的排序并集。"""
        ts_set: set[str] = set()
        for ks in series.values():
            for k in ks:
                ts_set.add(str(k["timestamp"]))
        return sorted(ts_set)

    def run(
        self,
        on_bars,
        ctx: PortfolioContext,
        series: dict[str, list[dict]],
        *,
        on_fill=None,
        on_funding=None,
        on_liquidation=None,
    ) -> dict[str, Any]:
        """跑完公共时间轴。``on_fill``/``on_funding``/``on_liquidation`` 是策略可选事件回调
        (docs/strategy-api.md §8);SPOT 组合仅 on_fill(无资金费/强平),PERP 组合三者皆派发(§10.6)。"""
        if not isinstance(ctx, PortfolioContext):
            raise TypeError("PortfolioEventLoop requires ctx to be PortfolioContext")
        if self.market_type == "PERP":
            return self._run_perp(
                on_bars, ctx, series, on_fill=on_fill, on_funding=on_funding, on_liquidation=on_liquidation
            )

        ctx.bind(self)
        ctx.set_series(series)
        self._series = series

        timeline = self._timeline(series)
        ptr = {s: 0 for s in series}
        self._ptr = ptr

        trades: list[_TradeRecord] = []
        equity_curve: list[dict] = []
        warnings: list[str] = []
        next_order_id = 1
        pending: list[OrderIntent] = []
        total = len(timeline)

        for step, ts in enumerate(timeline):
            # 推进各标的指针至 <= ts;识别本步新收盘的 bar(该标的的撮合快照)
            new_bars: dict[str, dict] = {}
            for s, ks in series.items():
                while ptr[s] < len(ks) and str(ks[ptr[s]]["timestamp"]) <= ts:
                    ptr[s] += 1
                if ptr[s] > 0 and str(ks[ptr[s] - 1]["timestamp"]) == ts:
                    new_bars[s] = ks[ptr[s] - 1]
                    self._last_close[s] = Decimal(str(ks[ptr[s] - 1]["close"]))
            ctx.set_index(ts, dict(ptr))

            # NEXT_BAR:撮合"上一时间轴步及更早"排队的订单。只撮合本步有新收盘 bar 的标的;
            # 缺 bar 的标的其挂单结转,等该标下一根可用 bar(仍是一次撮合机会,不重复)。
            remaining: list[OrderIntent] = []
            for intent in pending:
                if intent.symbol not in new_bars:
                    remaining.append(intent)
                    continue
                snap = self._snapshot(new_bars[intent.symbol])
                fill = matching.match(intent, snap, self.match_config)
                if fill is None:
                    if len(warnings) < 10:
                        warnings.append(
                            f"place_order returned None at {ts} "
                            f"({intent.symbol} {intent.order_type}/{intent.side})"
                        )
                    continue
                # 账本闸门(与单标的一致):BUY 现金足 / SELL 持仓足;共享现金池
                if intent.side == "BUY" and self.cash < fill.price * fill.qty + fill.fee:
                    log.warning("[portfolio] order rejected (insufficient cash) at %s", ts)
                    if len(warnings) < 10:
                        warnings.append(
                            f"order rejected (insufficient cash) at {ts} "
                            f"({intent.symbol} {intent.order_type}/{intent.side})"
                        )
                    continue
                if intent.side == "SELL" and self.position(intent.symbol).qty < fill.qty:
                    # dust 容差(matching-spec §7,与单标的 event_loop 同构):差额 < 1e-12
                    # 视为全平意图,clamp 到账本原值重撮合(fee 随 clamp 后 qty 重算)
                    clamped = clamp_dust_close(self.position(intent.symbol).qty, fill.qty)
                    if clamped is None:
                        log.warning("[portfolio] order rejected (insufficient inventory) at %s", ts)
                        if len(warnings) < 10:
                            warnings.append(
                                f"order rejected (insufficient inventory) at {ts} "
                                f"({intent.symbol} {intent.order_type}/{intent.side})"
                            )
                        continue
                    intent = replace(intent, amount=clamped)
                    fill = matching.match(intent, snap, self.match_config)
                    if fill is None:
                        continue
                self._apply_fill(
                    Fill(
                        order_id=next_order_id,
                        symbol=intent.symbol,
                        side=intent.side,
                        price=fill.price,
                        qty=fill.qty,
                        fee=fill.fee,
                        fee_currency=fill.fee_currency or "",
                        filled_at=fill.filled_at,
                    )
                )
                fill_order_id = next_order_id
                next_order_id += 1
                signed = fill.qty if intent.side == "BUY" else -fill.qty
                self.cash = self.cash - signed * fill.price - fill.fee
                trades.append(
                    _TradeRecord(
                        time=fill.filled_at or ts,
                        side=intent.side.lower(),
                        price=fill.price,
                        amount=fill.qty,
                        fee=fill.fee,
                        symbol=intent.symbol,
                    )
                )
                if on_fill is not None:
                    # 账本应用后派发:回调内读 ctx.position(symbol) 已含本笔成交(逐标的 payload)
                    _dispatch_callback(
                        on_fill,
                        FillEvent(
                            symbol=intent.symbol,
                            side=intent.side,
                            price=fill.price,
                            qty=fill.qty,
                            fee=fill.fee,
                            fee_currency=fill.fee_currency or "",
                            filled_at=fill.filled_at or ts,
                            order_id=fill_order_id,
                            liquidity=fill.liquidity,
                        ),
                        ctx,
                        where="on_fill",
                        at=ts,
                    )
            pending = remaining

            try:
                on_bars(ctx)
            except Exception as e:
                raise RuntimeError(f"strategy on_bars failed at {ts}: {e!r}") from e
            pending.extend(ctx.take_pending())

            equity_curve.append({"time": ts, "equity": self.current_equity()})

            if (step + 1) % PROGRESS_REPORT_EVERY == 0 or step == total - 1:
                ctx.report_progress(step + 1, total)

        # 末步排队的订单(含因标的缺 bar 一直未获撮合机会的结转挂单)不再执行
        leftover = len(pending)
        if leftover:
            warnings.append(f"末尾 bar 提交的 {leftover} 笔订单未参与撮合（回测区间已结束，NEXT_BAR 无下一根）")

        return _to_portfolio_section8(
            name="portfolio_backtest",
            params=self.params,
            symbols=self.symbols,
            timeframe=self.timeframe,
            timeline=timeline,
            trades=trades,
            equity_curve=equity_curve,
            positions=self.positions,
            warnings=warnings,
            reproducibility=self.reproducibility,
        )

    # ---------- PERP 组合主循环(spec §10.6) ----------

    def _perp_bar_marks(
        self, new_bars: dict[str, dict]
    ) -> dict[str, tuple[Decimal | None, Decimal | None, Decimal | None, Decimal]]:
        """强平输入:每个**非 flat** 持仓的 mark。有新 bar → (open,high,low,close);
        stale(本步无新 bar)→ (None,None,None,last_close)。用上一步末仓位判定(强平在撮合之前)。"""
        marks: dict[str, tuple[Decimal | None, Decimal | None, Decimal | None, Decimal]] = {}
        for sym, pos in self._perp.positions.items():
            if pos.signed_qty == 0:
                continue
            k = new_bars.get(sym)
            if k is not None:
                marks[sym] = (
                    Decimal(str(k["open"])),
                    Decimal(str(k["high"])),
                    Decimal(str(k["low"])),
                    Decimal(str(k["close"])),
                )
            else:
                marks[sym] = (None, None, None, self._last_close[sym])
        return marks

    def _run_perp(self, on_bars, ctx, series, *, on_fill, on_funding, on_liquidation) -> dict[str, Any]:
        ledger = PerpPortfolioLedger(
            initial_capital=self.initial_capital, taker_fee_rate=self.match_config.taker_fee_rate
        )
        self._perp = ledger
        # per-symbol 资金费回放游标(左开右闭归属 + catch-up,§10.5;各标独立缺期已在装配层 fail-closed)
        replays = {s: FundingReplay(self._funding_periods.get(s, []), self.timeframe) for s in self.symbols}
        # 每标的 PairSpec 预解析一次(单标的在 __init__ 缓存,组合在此对齐,避免撮合热循环重复 from_dict)
        pair_specs = {s: acceptance.PairSpec.from_dict(self._pair_specs.get(s)) for s in self.symbols}

        ctx.bind(self)
        ctx.set_series(series)
        self._series = series
        timeline = self._timeline(series)
        ptr = {s: 0 for s in series}
        self._ptr = ptr

        trades: list[_TradeRecord] = []
        equity_curve: list[dict] = []
        warnings: list[str] = []
        next_order_id = 1
        pending: list[OrderIntent] = []
        total = len(timeline)

        for step, ts in enumerate(timeline):
            new_bars: dict[str, dict] = {}
            for s, ks in series.items():
                while ptr[s] < len(ks) and str(ks[ptr[s]]["timestamp"]) <= ts:
                    ptr[s] += 1
                if ptr[s] > 0 and str(ks[ptr[s] - 1]["timestamp"]) == ts:
                    new_bars[s] = ks[ptr[s] - 1]
                    self._last_close[s] = Decimal(str(ks[ptr[s] - 1]["close"]))
            ctx.set_index(ts, dict(ptr))

            # 1. 强平(账户级 Model B,§10.4:先 ISOLATED 逐仓、后 CROSS;用上一步末各仓 × 本步 mark)
            for sym, rec in ledger.liquidate(ts, self._perp_bar_marks(new_bars)):
                trades.append(
                    _TradeRecord(
                        time=rec.timestamp,
                        side="sell" if rec.position_side == "LONG" else "buy",
                        price=rec.price,
                        amount=rec.qty,
                        fee=rec.fee,
                        symbol=sym,
                        position_effect="CLOSE_LONG" if rec.position_side == "LONG" else "CLOSE_SHORT",
                        liquidation=True,
                    )
                )
                warnings.append(
                    f"强平于 {rec.timestamp}：{sym} {rec.position_side} {rec.qty} @ {rec.price}（{rec.margin_mode}）"
                )
                if on_liquidation is not None:
                    _dispatch_callback(
                        on_liquidation,
                        LiquidationEvent(
                            symbol=sym,
                            timestamp=rec.timestamp,
                            position_side=rec.position_side,
                            qty=rec.qty,
                            price=rec.price,
                            realized_pnl=rec.gross_pnl - rec.fee,
                            margin_mode=rec.margin_mode,
                        ),
                        ctx,
                        where="on_liquidation",
                        at=rec.timestamp,
                    )

            # 2. 撮合上一步排队意图(只撮本步有新 bar 的标的;缺 bar 标的挂单结转)
            remaining: list[OrderIntent] = []
            for intent in pending:
                if intent.symbol not in new_bars:
                    remaining.append(intent)
                    continue
                snap = self._snapshot(new_bars[intent.symbol])
                pos = ledger.position(intent.symbol)
                flat = pos.signed_qty == 0
                eff_lev = intent.leverage if intent.leverage is not None else (None if flat else pos.leverage)
                eff_mm = intent.margin_mode if intent.margin_mode is not None else (None if flat else pos.margin_mode)
                # 接受性(§10.6:组合 PERP 与单标的同源,逐 symbol 过 pairSpecs)——leverage 值域/
                # maxLeverage/minQty/stepSize/tickSize 越界拒单进 warnings(而非账本闸门内 initial_margin
                # 抛 ValueError 崩溃整任务)。pairSpecs 缺该 symbol → UNKNOWN_SYMBOL fail-closed 全拒。
                acc = acceptance.check(
                    acceptance.AcceptInput(
                        symbol=intent.symbol,
                        market_type="PERP",
                        side=intent.side,
                        order_type=intent.order_type,
                        amount=intent.amount,
                        price=intent.price,
                        leverage=eff_lev,
                        margin_mode=eff_mm,
                        position_effect=intent.position_effect,
                    ),
                    pair_specs.get(intent.symbol),
                )
                if not acc.ok:
                    log.warning("[portfolio] order rejected (%s) at %s", acc.reason_code, ts)
                    if _reject_slots_left(warnings):
                        warnings.append(
                            f"order rejected ({acc.reason_code}: {acc.message}) at {ts} "
                            f"({intent.symbol} {intent.order_type}/{intent.position_effect})"
                        )
                    continue
                fill = matching.match(intent, snap, self.match_config)
                if fill is None:
                    if _reject_slots_left(warnings):
                        warnings.append(
                            f"place_order returned None at {ts} "
                            f"({intent.symbol} {intent.order_type}/{intent.position_effect})"
                        )
                    continue
                gate_reason = ledger.gate(
                    intent.symbol,
                    position_effect=intent.position_effect,
                    fill_qty=fill.qty,
                    fill_price=fill.price,
                    fee=fill.fee,
                    leverage=eff_lev,
                    margin_mode=eff_mm,
                )
                if gate_reason is not None:
                    log.warning("[portfolio] %s at %s", gate_reason, ts)
                    if _reject_slots_left(warnings):
                        warnings.append(
                            f"{gate_reason} at {ts} ({intent.symbol} {intent.order_type}/{intent.position_effect})"
                        )
                    continue
                ledger.apply_fill(
                    intent.symbol,
                    position_effect=intent.position_effect,
                    fill_qty=fill.qty,
                    fill_price=fill.price,
                    fee=fill.fee,
                    leverage=eff_lev,
                    margin_mode=eff_mm,
                )
                trades.append(
                    _TradeRecord(
                        time=fill.filled_at or ts,
                        side=intent.side.lower(),
                        price=fill.price,
                        amount=fill.qty,
                        fee=fill.fee,
                        symbol=intent.symbol,
                        position_effect=intent.position_effect,
                    )
                )
                fill_order_id = next_order_id
                next_order_id += 1
                if on_fill is not None:
                    _dispatch_callback(
                        on_fill,
                        FillEvent(
                            symbol=intent.symbol,
                            side=intent.side,
                            price=fill.price,
                            qty=fill.qty,
                            fee=fill.fee,
                            fee_currency=fill.fee_currency or "",
                            filled_at=fill.filled_at or ts,
                            order_id=fill_order_id,
                            liquidity=fill.liquidity,
                            position_effect=intent.position_effect,
                        ),
                        ctx,
                        where="on_fill",
                        at=ts,
                    )
            pending = remaining

            # 3. on_bars(策略产生新意图,NEXT_BAR)
            try:
                on_bars(ctx)
            except Exception as e:
                raise RuntimeError(f"strategy on_bars failed at {ts}: {e!r}") from e
            pending.extend(ctx.take_pending())

            # 4. 资金费(§10.5:逐有新 bar 标的结算归属期次,flat 跳过,mark 真值优先)
            for s in new_bars:
                for period in replays[s].periods_for_bar(ts):
                    if ledger.is_flat(s):
                        continue
                    mark = (
                        period.mark_price
                        if period.mark_price is not None and period.mark_price > 0
                        else self._last_close[s]
                    )
                    amount = ledger.settle_funding_period(s, period.settled_rate, mark)
                    if on_funding is not None:
                        fts = period.funding_time.isoformat().replace("+00:00", "Z")
                        _dispatch_callback(
                            on_funding,
                            FundingEvent(
                                symbol=s,
                                funding_time=fts,
                                settled_rate=period.settled_rate,
                                amount=amount,
                                qty_at_settle=abs(ledger.position(s).signed_qty),
                                mark_price=mark,
                                source=period.source,
                            ),
                            ctx,
                            where="on_funding",
                            at=fts,
                        )

            # 5. equity 收尾(账户 equity(各标 current mark),含 margin_used/funding_cum)
            equity_curve.append(
                {
                    "time": ts,
                    "equity": ledger.equity(self._last_close),
                    "margin_used": ledger.sum_iso_margin(),
                    "funding_cum": ledger.funding_cum,
                }
            )

            if (step + 1) % PROGRESS_REPORT_EVERY == 0 or step == total - 1:
                ctx.report_progress(step + 1, total)

        leftover = len(pending)
        if leftover:
            warnings.append(f"末尾 bar 提交的 {leftover} 笔订单未参与撮合（回测区间已结束，NEXT_BAR 无下一根）")
        # 尾部漏期诊断(§10.5:K 线提前结束时任务 end 前未回放的已结算期次,逐标的标注)。
        # task_end 解析一次(纯诊断不得拖垮结果:不可解析时降级为一条 warning,不静默吞)。
        if self._task_end is not None:
            try:
                end_dt = _parse_task_end(self._task_end)
            except (ValueError, TypeError) as ex:
                warnings.append(f"task_end {self._task_end!r} 不可解析（{ex!r}），尾部漏期检测跳过")
            else:
                for s in self.symbols:
                    for p in replays[s].pending_periods():
                        if p.funding_time <= end_dt:
                            warnings.append(
                                f"K 线提前结束：{s} 尾部已结算资金费期次 {p.funding_time.isoformat().replace('+00:00', 'Z')} 未参与回放"
                            )

        return _to_portfolio_perp_section8(
            name="portfolio_backtest",
            params=self.params,
            symbols=self.symbols,
            timeframe=self.timeframe,
            timeline=timeline,
            trades=trades,
            equity_curve=equity_curve,
            positions=ledger.positions,
            warnings=warnings,
            reproducibility=self.reproducibility,
        )


def _parse_task_end(ts: str):
    """task_end ISO-8601 → aware datetime(尾部漏期诊断用);复用 perp_ledger.parse_instant 纪律。"""
    from kwikquant_worker.backtest.perp_ledger import parse_instant

    return parse_instant(ts)


def _to_portfolio_section8(
    *,
    name: str,
    params: dict,
    symbols: list[str],
    timeframe: str,
    timeline: list[str],
    trades: list[_TradeRecord],
    equity_curve: list[dict],
    positions: dict[str, Position],
    warnings: list[str],
    reproducibility: dict[str, Any],
) -> dict[str, Any]:
    """组合回测结果 JSON。与单标的 section8 同构,额外带 ``symbols`` + 分标的 ``positions``,
    且每笔 trade 携带 ``symbol``。``metrics`` 留空由 Java ``PerformanceCalculator`` 重算。"""
    period_start = timeline[0] if timeline else ""
    period_end = timeline[-1] if timeline else ""
    params_snapshot = dict(params)
    params_snapshot["_kwikquant"] = {**reproducibility, "warnings": warnings}
    return {
        "name": name,
        "params": params_snapshot,
        "symbol": ",".join(symbols),
        "symbols": list(symbols),
        "timeframe": timeframe,
        "period": {"start": str(period_start), "end": str(period_end)},
        "trades": [
            {
                "time": t.time,
                "symbol": getattr(t, "symbol", None),
                "side": t.side,
                "price": str(t.price),
                "amount": str(t.amount),
                "fee": str(t.fee),
            }
            for t in trades
        ],
        "equity_curve": [{"time": e["time"], "equity": str(e["equity"])} for e in equity_curve],
        "positions": {
            sym: {"qty": str(p.qty), "avg_price": str(p.avg_price)} for sym, p in positions.items()
        },
        "metrics": {},
        "warnings": warnings,
    }


def _to_portfolio_perp_section8(
    *,
    name: str,
    params: dict,
    symbols: list[str],
    timeframe: str,
    timeline: list[str],
    trades: list[_TradeRecord],
    equity_curve: list[dict],
    positions: dict[str, "PerpPosition"],
    warnings: list[str],
    reproducibility: dict[str, Any],
) -> dict[str, Any]:
    """组合 PERP 结果 JSON(spec §10.7)。在组合形态(symbols + 分标的 positions + trade 带 symbol)
    之上叠加 PERP 字段:顶层 market_type/liquidation_model,trade 带 position_effect(强平行 liquidation),
    equity_curve 带 margin_used/funding_cum,positions 是 signed 净持仓 + leverage/margin_mode/margin。
    金额全 ``str(Decimal)`` + 负零规范化(norm)。``metrics`` 留空由 Java PerformanceCalculator 重算。"""
    period_start = timeline[0] if timeline else ""
    period_end = timeline[-1] if timeline else ""
    params_snapshot = dict(params)
    params_snapshot["_kwikquant"] = {**reproducibility, "warnings": warnings}

    def trade_row(t: _TradeRecord) -> dict[str, Any]:
        # 金额一律过 norm(-0 纪律,spec §8/§10.7):与单标的 event_loop._to_section8 逐字对齐,
        # 否则零 fee/精确零场景两侧序列化分叉,破坏 §10.9 N=1 逐字节一致红线
        row: dict[str, Any] = {
            "time": t.time,
            "symbol": t.symbol,
            "side": t.side,
            "price": str(norm(t.price)),
            "amount": str(norm(t.amount)),
            "fee": str(norm(t.fee)),
            "position_effect": t.position_effect,
        }
        if t.liquidation:
            row["liquidation"] = True
        return row

    def pos_row(p: "PerpPosition") -> dict[str, Any]:
        return {
            "qty": str(norm(p.signed_qty)),
            "avg_price": str(p.avg_price) if p.avg_price is not None else "0",
            "leverage": p.leverage,
            "margin_mode": p.margin_mode,
            "margin": str(norm(p.margin)),
        }

    return {
        "name": name,
        "params": params_snapshot,
        "symbol": ",".join(symbols),
        "symbols": list(symbols),
        "timeframe": timeframe,
        "period": {"start": str(period_start), "end": str(period_end)},
        "market_type": "PERP",
        "liquidation_model": "BAR_EXTREME_APPROX",
        "trades": [trade_row(t) for t in trades],
        "equity_curve": [
            {
                "time": e["time"],
                "equity": str(norm(e["equity"])),
                "margin_used": str(norm(e["margin_used"])),
                "funding_cum": str(norm(e["funding_cum"])),
            }
            for e in equity_curve
        ],
        "positions": {sym: pos_row(p) for sym, p in positions.items() if p.signed_qty != 0},
        "metrics": {},
        "warnings": warnings,
    }
