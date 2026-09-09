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

from kwikquant_worker.backtest import matching
from kwikquant_worker.backtest.matching import MatchConfig, OrderIntent
from kwikquant_worker.context import OrderAck, clamp_dust_close, normalize_order
from kwikquant_worker.event_loop import PROGRESS_REPORT_EVERY, _bar_from_kline, _TradeRecord
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
        """全组合共享的可用现金。直接读引擎账本。"""
        if self._loop is None:
            return Decimal(0)
        return self._loop.cash

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
        本任务标的列表内;side ∈ BUY/SELL;order_type 枚举;amount/price **拒 float** 且 > 0。
        返 ``OrderAck(accepted=True)`` = 已排队(NEXT_BAR 成交,拒单异步进 warnings)。

        组合回测**仅 SPOT**(docs/perp-backtest-spec.md §1):PERP 任务在 Java 提交入口与
        worker 装配层已拒,此处为纵深防御(合约字段经 normalize SPOT 规则同拒)。
        """
        if self._market_type == "PERP":
            raise ValueError("portfolio backtest is SPOT-only (PERP 组合回测不支持,见 perp-backtest-spec §1)")
        sym = self._require_symbol(symbol)
        if sym not in self._symbols:
            raise ValueError(
                f"place_order symbol 非法: {sym!r}(不在本任务标的列表 {self._symbols})"
            )
        o = normalize_order(
            market_type="SPOT",
            side=side,
            order_type=order_type,
            amount=amount,
            price=price,
            position_effect=position_effect,
            leverage=leverage,
            margin_mode=margin_mode,
        )
        self._pending.append(
            OrderIntent(symbol=sym, side=o.side, order_type=o.order_type, amount=o.amount, price=o.price)
        )
        return OrderAck(accepted=True)

    def close_position(self, symbol: str | None = None) -> OrderAck:
        """市价全平该标的持仓(账本原值下单,绕开精度残差)。无持仓返
        ``OrderAck(accepted=False, reason="NO_POSITION")``。排队语义同 place_order。"""
        sym = self._require_symbol(symbol)
        pos = self.position(sym)
        if pos.qty == 0:
            return OrderAck(accepted=False, reason="NO_POSITION")
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
    ) -> None:
        self.initial_capital = initial_capital
        self.symbols = list(symbols or [])
        self.timeframe = timeframe
        self.params = params or {}
        self.reproducibility = reproducibility or {}
        self.match_config = MatchConfig.from_dict(matching_config)
        # 引擎内部真实账本(全组合共享现金 + 分标的持仓)
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
        p = self.positions.get(symbol)
        if p is None:
            return Position(symbol=symbol, qty=Decimal(0), avg_price=Decimal(0))
        return Position(symbol=p.symbol, qty=p.qty, avg_price=p.avg_price)

    def current_equity(self) -> Decimal:
        """现金 + Σ(持仓 × 该标最新已收盘 close)。"""
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

    def run(self, on_bars, ctx: PortfolioContext, series: dict[str, list[dict]]) -> dict[str, Any]:
        if not isinstance(ctx, PortfolioContext):
            raise TypeError("PortfolioEventLoop requires ctx to be PortfolioContext")

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
