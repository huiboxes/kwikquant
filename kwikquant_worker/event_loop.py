"""EventLoop — 回测 / Runner 事件驱动。

函数式:策略是顶层 ``def on_bar(bar, ctx):``,event_loop 逐 bar set klines+index
→ 先**本地撮合**上一 bar 产生的订单意图(``backtest/matching.py``,零 HTTP)
→ 调 ``on_bar(bar, ctx)`` → 维护 cash/equity(Decimal)→ 汇总回测结果 JSON。
行情(bar.open/close…)用 float 给用户;内部金额(cash/equity/holdings)用 Decimal,
从 k 原始 str 转(不绕 float,保精度)。

撮合与接受性语义单一真相源:``docs/matching-spec.md``(NEXT_BAR 时序/账本闸门 §7、接受性 §9);
PERP 回测(净持仓账本/bar 极值强平/资金费期次回放,``backtest/perp_ledger.py``)语义在
``docs/perp-backtest-spec.md``。SPOT 路径与引入 PERP 前逐字节一致(回归 diff=0)。
"""

from __future__ import annotations

import asyncio
import logging
import sys
from dataclasses import dataclass, replace
from decimal import Decimal
from typing import TYPE_CHECKING, Any

from kwikquant_worker import acceptance
from kwikquant_worker.backtest import matching
from kwikquant_worker.backtest.matching import MatchConfig
from kwikquant_worker.backtest.perp_ledger import FundingReplay, PerpLedger, norm
from kwikquant_worker.context import clamp_dust_close
from kwikquant_worker.strategy import Bar, BacktestContext, Fill, Position

if TYPE_CHECKING:
    from kwikquant_worker.backtest.perp_ledger import FundingPeriod
    from kwikquant_worker.health_signals import HealthSignals

log = logging.getLogger(__name__)

# 逐 bar 进度上报节流间隔:每 N bar 上报一次(8760 bar → ~44 次 HTTP,开销可接受)。
# 末根 bar 强制上报,保证最终 100%。上报走已有 service_token HTTP 通道(同 place_order)。
PROGRESS_REPORT_EVERY = 200


_REJECT_WARNING_PREFIXES = ("order rejected", "place_order returned None")


def _reject_slots_left(warnings: list) -> bool:
    """拒单类 warning 独立 10 条预算(docs/perp-backtest-spec.md §8):不与强平/资金费代理/
    末根 bar 标注共享数组长度竞争——强平多发时拒单诊断不被挤出。"""
    return sum(1 for w in warnings if w.startswith(_REJECT_WARNING_PREFIXES)) < 10


@dataclass
class _TradeRecord:
    time: str
    side: str
    price: Decimal
    amount: Decimal
    fee: Decimal
    # 组合(多标的)回测的成交标的;单标的回测不填(标的即报告 symbol,保持存量输出不变)
    symbol: str | None = None
    # PERP 扩展(SPOT 保持 None/False,序列化省略键 → 存量输出逐字节不变):
    # 用户视角四向意图(穿零反转拆两段内核调用仍记一条);强平成交标记
    position_effect: str | None = None
    liquidation: bool = False


class BacktestEventLoop:
    """逐 bar 驱动 ``on_bar(bar, ctx)``,汇总 trades + equity_curve 输出回测结果 JSON。

    PERP(``market_type="PERP"``,docs/perp-backtest-spec.md):净持仓账本走
    :class:`PerpLedger`(钱数学委托 perp_math 内核),逐 bar 顺序 = 强平 → acceptance →
    撮合 → 账本闸门 → on_bar → 资金费期次回放 → equity;需要 ``pair_specs``(接受性快照)
    与 ``funding_periods``(已结算资金费序列,ASC)。SPOT 路径不变。
    """

    def __init__(
        self,
        *,
        initial_capital: Decimal = Decimal("100000"),
        symbol: str = "",
        timeframe: str = "",
        params: dict[str, Any] | None = None,
        reproducibility: dict[str, Any] | None = None,
        matching_config: dict[str, Any] | None = None,
        market_type: str = "SPOT",
        pair_specs: dict[str, dict] | None = None,
        funding_periods: "list[FundingPeriod] | None" = None,
    ) -> None:
        self.initial_capital = initial_capital
        self.symbol = symbol
        self.timeframe = timeframe
        self.params = params or {}
        self.reproducibility = reproducibility or {}
        # 本地撮合配置(Java Gateway 下发快照);缺省 MatchConfig.defaults()(spec §2 两侧一致)
        self.match_config = MatchConfig.from_dict(matching_config)
        self.market_type = market_type
        # 接受性快照(symbol → Java 下发的 camelCase dict)。PERP:缺失该 symbol → UNKNOWN_SYMBOL
        # fail-closed 全拒(perp-backtest-spec §2);SPOT:pair_specs 未下发 → None = 存量行为
        # (跳过 acceptance,匹配 matching-spec §7 接受性闸门条款)。
        self._pair_spec = acceptance.PairSpec.from_dict((pair_specs or {}).get(symbol))
        self._funding_periods = funding_periods
        # ctx.equity()/available_cash() 直读的引擎账本状态(run() 内推进;ctx.bind 后可见)
        self._cash: Decimal = initial_capital
        self._last_close: Decimal | None = None
        self._ledger: PerpLedger | None = None
        self._ctx: BacktestContext | None = None

    # ---------- 账本读取(策略 ctx 经 bind 反查,与 PortfolioEventLoop 同构) ----------

    def current_equity(self) -> Decimal:
        """账户权益(quote 计)。SPOT=现金+持仓×最新已收盘 close;PERP=账本 equity 口径
        (现金含锁定保证金 + 未实现盈亏,docs/perp-backtest-spec.md §3)。"""
        if self.market_type == "PERP":
            if self._ledger is None:
                return self._cash
            if self._last_close is None:
                return self._ledger.cash
            return self._ledger.equity(self._last_close)
        holdings = Decimal(0)
        if self._ctx is not None and self.symbol and self._last_close is not None:
            pos = self._ctx.position(self.symbol)
            if pos.qty != 0:
                holdings = pos.qty * self._last_close
        return self._cash + holdings

    def available_cash(self) -> Decimal:
        """可用现金(quote 计)。SPOT=账本现金;PERP=现金−锁定保证金(ledger.available)。"""
        if self.market_type == "PERP" and self._ledger is not None:
            return self._ledger.available()
        return self._cash

    def run(self, on_bar, ctx: BacktestContext, klines: list[dict]) -> dict[str, Any]:
        if not isinstance(ctx, BacktestContext):
            raise TypeError("BacktestEventLoop requires ctx to be BacktestContext")

        # ctx.equity()/available_cash() 经 bind 直读引擎账本(与 PortfolioEventLoop 同构)
        ctx.bind(self)
        self._ctx = ctx

        perp = self.market_type == "PERP"
        ledger: PerpLedger | None = None
        replay: FundingReplay | None = None
        if perp:
            # fail-closed:PERP 必须给已结算资金费序列(数据缺失在 worker_server 层拦为
            # FUNDING_DATA_MISSING exit 3,此处防装配错误)
            if self._funding_periods is None:
                raise ValueError("PERP backtest requires funding_periods (settled funding series)")
            ledger = PerpLedger(
                initial_capital=self.initial_capital,
                taker_fee_rate=self.match_config.taker_fee_rate,
            )
            replay = FundingReplay(self._funding_periods, self.timeframe)
            self._ledger = ledger

        ctx.set_klines(klines)
        trades: list[_TradeRecord] = []
        equity_curve: list[dict] = []
        warnings: list[str] = []
        total = len(klines)
        next_order_id = 1

        for i, k in enumerate(klines):
            ctx.set_index(i)
            bar = Bar(
                timestamp=str(k["timestamp"]),
                open=float(str(k["open"])),
                high=float(str(k["high"])),
                low=float(str(k["low"])),
                close=float(str(k["close"])),
                volume=float(str(k.get("volume", 0))),
            )
            # 撮合快照:用原始 str 保 Decimal 精度(不绕 float)。last=close:FAST 市价单用 last。
            snapshot = {
                "timestamp": bar.timestamp,
                "open": str(k["open"]),
                "high": str(k["high"]),
                "low": str(k["low"]),
                "close": str(k["close"]),
                "last": str(k["close"]),
                "volume": str(k.get("volume", 0)),
            }

            # NEXT_BAR(spec §7):策略在上一根 bar 收盘后才得到完整 OHLC,订单最早只能用当前
            # (下一根)bar 撮合。take_pending 取走上一 bar on_bar 排队的意图,本 bar 本地撮合。
            if perp:
                # PERP 撮合段(perp-backtest-spec §6 步骤 1-2):强平先于撮合 → acceptance →
                # 撮合 → 账本闸门 → 内核应用
                self._perp_match_bar(ctx, ledger, bar, k, snapshot, trades, warnings)
            else:
                for intent in ctx.take_pending():
                    # 接受性闸门(matching-spec §7/§9):pairSpecs 快照可得时先过 acceptance,
                    # 拒单进 warnings(不再静默);未下发快照保持存量行为(跳过)
                    if self._pair_spec is not None:
                        acc = acceptance.check(
                            acceptance.AcceptInput(
                                symbol=intent.symbol,
                                market_type=self.market_type,
                                side=intent.side,
                                order_type=intent.order_type,
                                amount=intent.amount,
                                price=intent.price,
                            ),
                            self._pair_spec,
                        )
                        if not acc.ok:
                            log.warning(
                                "[event_loop] order rejected (%s) at %s", acc.reason_code, bar.timestamp
                            )
                            if _reject_slots_left(warnings):
                                warnings.append(
                                    f"order rejected ({acc.reason_code}: {acc.message}) at {bar.timestamp} "
                                    f"({intent.order_type}/{intent.side})"
                                )
                            continue
                    fill = matching.match(intent, snapshot, self.match_config)
                    if fill is None:
                        if _reject_slots_left(warnings):
                            warnings.append(
                                f"place_order returned None at {bar.timestamp} "
                                f"({intent.order_type}/{intent.side})"
                            )
                        continue
                    # 账本闸门(原 Java 回测账本 canApply 语义,spec §7):BUY 现金足 / SELL 持仓足
                    if intent.side == "BUY" and self._cash < fill.price * fill.qty + fill.fee:
                        log.warning("[event_loop] order rejected (insufficient cash) at %s", bar.timestamp)
                        if _reject_slots_left(warnings):
                            warnings.append(
                                f"order rejected (insufficient cash) at {bar.timestamp} "
                                f"({intent.order_type}/{intent.side})"
                            )
                        continue
                    if intent.side == "SELL" and ctx.position(intent.symbol).qty < fill.qty:
                        # dust 容差(spec §7):差额 < 1e-12 视为全平意图,clamp 到账本原值重撮合
                        # (fee 随 clamp 后 qty 重算)——消灭 Decimal 运算残差的假拒单
                        clamped = clamp_dust_close(ctx.position(intent.symbol).qty, fill.qty)
                        if clamped is None:
                            log.warning("[event_loop] order rejected (insufficient inventory) at %s", bar.timestamp)
                            if _reject_slots_left(warnings):
                                warnings.append(
                                    f"order rejected (insufficient inventory) at {bar.timestamp} "
                                    f"({intent.order_type}/{intent.side})"
                                )
                            continue
                        intent = replace(intent, amount=clamped)
                        fill = matching.match(intent, snapshot, self.match_config)
                        if fill is None:
                            continue
                    ctx._apply_fill(
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
                    self._cash = self._cash - signed * fill.price - fill.fee
                    trades.append(
                        _TradeRecord(
                            time=fill.filled_at or bar.timestamp,
                            side=intent.side.lower(),
                            price=fill.price,
                            amount=fill.qty,
                            fee=fill.fee,
                        )
                    )

            # 最新已收盘 close 在 on_bar 前落账本状态:策略在 on_bar 内调 ctx.equity() 读到
            # 当前 bar 口径(策略已拿到 bar.close,无信息差;PERP 资金费回放在 on_bar 后,
            # equity 曲线仍按 spec §6 顺序记录)
            close_dec = Decimal(str(k["close"]))  # 原始 str 转,保精度
            self._last_close = close_dec

            try:
                on_bar(bar, ctx)
            except Exception as e:
                raise RuntimeError(f"strategy on_bar failed at {bar.timestamp}: {e!r}") from e

            if perp:
                # PERP 结算段(perp-backtest-spec §6 步骤 4-5):资金费期次回放 → equity
                self._perp_settle_bar(ctx, ledger, replay, bar, close_dec, equity_curve)
            else:
                equity_curve.append({"time": bar.timestamp, "equity": self.current_equity()})

            # 进度上报(节流:每 PROGRESS_REPORT_EVERY bar 或末根;失败容错见 ctx.report_progress)
            if (i + 1) % PROGRESS_REPORT_EVERY == 0 or i == total - 1:
                ctx.report_progress(i + 1, total)

        leftover = len(ctx._pending)
        if leftover:
            warnings.append(f"末尾 bar 提交的 {leftover} 笔订单未参与撮合（回测区间已结束，NEXT_BAR 无下一根）")
        if perp:
            proxy_count = sum(1 for p in self._funding_periods if p.source == "PROXY_BINANCE")
            if proxy_count:
                # 跨所代理显性标注(基差风险,spec §8;代理是提交时 allowFundingProxy 的显式决定)
                warnings.append(
                    f"资金费跨所代理：序列含 {proxy_count} 期 PROXY_BINANCE 代理费率"
                    f"（存在跨所基差；持仓跨越这些期次时按代理值结算，成本与本所真值有偏差）"
                )
        return _to_section8(
            name="backtest",
            params=self.params,
            symbol=self.symbol,
            timeframe=self.timeframe,
            klines=klines,
            trades=trades,
            equity_curve=equity_curve,
            warnings=warnings,
            reproducibility=self.reproducibility,
            market_type=self.market_type,
        )

    # ---------- PERP 逐 bar 编排(docs/perp-backtest-spec.md §6) ----------

    def _perp_match_bar(self, ctx, ledger, bar, k, snapshot, trades, warnings) -> None:
        """强平(先于本 bar 新订单撮合,spec §4.1 规则 4)→ 意图:acceptance → 撮合 → 闸门 → 应用。"""
        liq = ledger.check_liquidation(
            timestamp=bar.timestamp,
            open_=Decimal(str(k["open"])),
            high=Decimal(str(k["high"])),
            low=Decimal(str(k["low"])),
        )
        if liq is not None:
            trades.append(
                _TradeRecord(
                    time=liq.timestamp,
                    side="sell" if liq.position_side == "LONG" else "buy",
                    price=liq.price,
                    amount=liq.qty,
                    fee=liq.fee,
                    position_effect="CLOSE_LONG" if liq.position_side == "LONG" else "CLOSE_SHORT",
                    liquidation=True,
                )
            )
            warnings.append(
                f"强平于 {liq.timestamp}：{liq.position_side} {liq.qty} @ {liq.price}"
                f"（{liq.margin_mode}）"
            )

        for intent in ctx.take_pending():
            # leverage/marginMode 有效值:首仓取 intent(必填由 acceptance 规则 12/13 判),
            # 持仓中缺省继承(spec §2);显式矛盾由 ledger.gate 拒(LEVERAGE/MARGIN_MODE_MISMATCH)
            eff_lev = intent.leverage if intent.leverage is not None else (
                None if ledger.is_flat() else ledger.pos.leverage
            )
            eff_mm = intent.margin_mode if intent.margin_mode is not None else (
                None if ledger.is_flat() else ledger.pos.margin_mode
            )
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
                self._pair_spec,
            )
            if not acc.ok:
                log.warning("[event_loop] order rejected (%s) at %s", acc.reason_code, bar.timestamp)
                if _reject_slots_left(warnings):
                    warnings.append(
                        f"order rejected ({acc.reason_code}: {acc.message}) at {bar.timestamp} "
                        f"({intent.order_type}/{intent.position_effect})"
                    )
                continue
            fill = matching.match(intent, snapshot, self.match_config)
            if fill is None:
                if _reject_slots_left(warnings):
                    warnings.append(
                        f"place_order returned None at {bar.timestamp} "
                        f"({intent.order_type}/{intent.position_effect})"
                    )
                continue
            gate_reason = ledger.gate(
                position_effect=intent.position_effect,
                fill_qty=fill.qty,
                fill_price=fill.price,
                fee=fill.fee,
                leverage=eff_lev,
                margin_mode=eff_mm,
            )
            if gate_reason is not None:
                log.warning("[event_loop] %s at %s", gate_reason, bar.timestamp)
                if _reject_slots_left(warnings):
                    warnings.append(
                        f"{gate_reason} at {bar.timestamp} ({intent.order_type}/{intent.position_effect})"
                    )
                continue
            ledger.apply_fill(
                position_effect=intent.position_effect,
                fill_qty=fill.qty,
                fill_price=fill.price,
                fee=fill.fee,
                leverage=eff_lev,
                margin_mode=eff_mm,
            )
            # trade 记录用户视角一条(穿零反转的两段内核调用不拆行,spec §3.3)
            trades.append(
                _TradeRecord(
                    time=fill.filled_at or bar.timestamp,
                    side=intent.side.lower(),
                    price=fill.price,
                    amount=fill.qty,
                    fee=fill.fee,
                    position_effect=intent.position_effect,
                )
            )
        self._sync_position_view(ctx, ledger, Decimal(str(k["close"])))

    def _perp_settle_bar(self, ctx, ledger, replay, bar, close_dec, equity_curve) -> None:
        """资金费期次回放((bar_open, bar_open+timeframe] 左开右闭,spec §5)→ equity 记录。"""
        for period in replay.periods_for_bar(bar.timestamp):
            ledger.settle_funding_period(period.settled_rate, close_dec)
        self._sync_position_view(ctx, ledger, close_dec)
        equity_curve.append(
            {
                "time": bar.timestamp,
                "equity": ledger.equity(close_dec),
                "margin_used": ledger.margin_used,
                "funding_cum": ledger.funding_cum,
            }
        )

    def _sync_position_view(self, ctx, ledger, close_dec) -> None:
        """引擎账本 → ctx.position() 视图(signed qty + PERP 扩展字段;position() 返副本防篡改)。"""
        if ledger.is_flat():
            ctx._positions[self.symbol] = Position(symbol=self.symbol, qty=Decimal(0), avg_price=Decimal(0))
        else:
            ctx._positions[self.symbol] = Position(
                symbol=self.symbol,
                qty=ledger.pos.signed_qty,
                avg_price=ledger.pos.avg_price,
                leverage=ledger.pos.leverage,
                margin_mode=ledger.pos.margin_mode,
                margin=ledger.pos.margin,
                liquidation_price=ledger.liquidation_reference_price(),
                unrealized_pnl=ledger.unrealized(close_dec),
            )


def _bar_from_kline(payload: dict) -> Bar:
    """Kline dict(``{openTime, open, high, low, close, volume}``)→ ``Bar``(行情 float)。

    WS ``/topic/kline`` payload 与 REST ``/api/v1/market/klines`` Kline record 同键(openTime),
    共用此映射;多余字段(exchange/marketType/symbol/interval)忽略。``_on_kline`` 与 runner 历史
    bar 预填(``worker_server._prefill_history``)都经此构造 Bar,保证 WS 与预填 bar 同型。"""
    return Bar(
        timestamp=str(payload.get("openTime", "")),
        open=float(str(payload.get("open", 0))),
        high=float(str(payload.get("high", 0))),
        low=float(str(payload.get("low", 0))),
        close=float(str(payload.get("close", 0))),
        volume=float(str(payload.get("volume", 0))),
    )


class RunnerEventLoop:
    """模拟盘/实盘长驻循环 — StreamClient 订阅 /topic/kline → bar 关闭检测 → on_bar(bar, ctx)。

    与回测 BacktestEventLoop 对偶:回测逐 bar 喂历史(on_bar 每根),实盘 WS 推 kline 实时
    更新(尾根替换),runner 做 bar 关闭检测(openTime 变化=前一根关闭→用前一根调 on_bar)。
    函数式 on_bar(bar, ctx) 与回测统一(用户一份策略通吃回测+live)。止损止盈靠交易所条件单
    (OKX stop-limit/OCO,on_bar 内 ctx.place_order 下条件单),不依赖 on_tick。
    """

    def __init__(self, health_signals: "HealthSignals | None" = None) -> None:
        self._current_bar: Bar | None = None
        self._on_bar = None
        self._ctx = None
        self._signals = health_signals

    def run(
        self,
        on_bar,
        ctx,
        stream_client,
        *,
        exchange: str,
        market_type: str,
        symbol: str,
        interval: str,
    ) -> None:
        """注册 kline handler → asyncio.run(stream_client.run()) 长驻。

        exchange/market_type/symbol/interval 用于 on_kline topic 订阅(对齐后端 KLINE_TOPIC_FORMAT)。
        """
        self._on_bar = on_bar
        self._ctx = ctx
        self._current_bar = None
        stream_client.on_kline(exchange, market_type, symbol, interval, self._on_kline)
        # 订阅 /topic/ticker 触发后端 onWsSubscribe 起 ticker worker(WS 驱动 persistent=false)。
        # PAPER 撮合靠 PaperExecutor.onTicker(ticker push);非 persistent 币的 ticker worker
        # 不订阅就不跑 → 撮合不发生。runner 自身不消费 ticker(策略用 on_bar),_on_tick 是 no-op。
        stream_client.on_tick(exchange, market_type, symbol, self._on_tick)
        try:
            asyncio.run(stream_client.run())
        except KeyboardInterrupt:
            pass

    def _touch_ws(self) -> None:
        if self._signals is not None:
            self._signals.touch_ws_msg()

    def _touch_bar(self) -> None:
        if self._signals is not None:
            self._signals.touch_bar()

    def _record_on_bar(self, *, ok: bool) -> None:
        if self._signals is not None:
            self._signals.record_on_bar_outcome(ok=ok)

    def _on_tick(self, payload: dict) -> None:
        """ticker 回调 — no-op。订阅 /topic/ticker 仅为触发后端起 ticker worker(WS 驱动),
        让 PaperExecutor.onTicker 收到 ticker push 完成撮合。runner 自身不消费 ticker(策略用 on_bar)。
        """
        return

    async def _on_kline(self, payload: dict) -> None:
        """bar 关闭检测:openTime 前进=前一根关闭 → on_bar(前一根)+ set_bar。

        首根只缓存;同 openTime 更新覆盖;倒退忽略。on_bar 在 asyncio.to_thread 跑(同步
        place_order HTTP 阻塞线程不阻塞 event loop;支持 1m bar,WS 心跳不被卡)。
        """
        self._touch_ws()
        bar = _bar_from_kline(payload)
        if self._current_bar is None:
            self._current_bar = bar
            return
        if bar.timestamp > self._current_bar.timestamp:
            closed = self._current_bar
            self._current_bar = bar  # 新 bar(未关闭)
            # to_thread: on_bar 含同步 place_order HTTP,阻塞线程不阻塞 event loop
            await asyncio.to_thread(self._invoke_on_bar, closed)
        elif bar.timestamp == self._current_bar.timestamp:
            # 同 openTime 更新(尾根替换),覆盖最终值
            self._current_bar = bar
        # bar.timestamp < current(倒退,网络重连返旧 candle)→ 忽略,不触发不覆盖

    def _invoke_on_bar(self, closed: Bar) -> None:
        """同步调 on_bar(set_bar + 用户 on_bar,含 place_order HTTP)。异常容错(记 stderr 继续)。"""
        assert self._ctx is not None and self._on_bar is not None
        self._ctx.set_bar(closed)
        # runner on_bar 容错:记 stderr 继续——长驻进程不因单根 bar 的策略异常而死;
        # 与回测 fail-fast 整个任务**有意不同**(能力矩阵见 docs/strategy-api.md §6)。
        try:
            self._on_bar(closed, self._ctx)
        except Exception as e:  # noqa: BLE001
            self._record_on_bar(ok=False)
            print(f"[runner] on_bar raised at {closed.timestamp}: {e!r}", file=sys.stderr)
        else:
            self._record_on_bar(ok=True)
        finally:
            self._touch_bar()


def _to_section8(
    *,
    name: str,
    params: dict,
    symbol: str,
    timeframe: str,
    klines: list[dict],
    trades: list[_TradeRecord],
    equity_curve: list[dict],
    warnings: list[str],
    reproducibility: dict[str, Any],
    market_type: str = "SPOT",
) -> dict[str, Any]:
    """回测结果 section8 JSON。SPOT 输出与引入 PERP 前逐字节一致(条件字段仅 PERP 出现);
    PERP 扩展见 docs/perp-backtest-spec.md §8(负零经 norm 规范化,严禁 "-0" 进 JSON)。"""
    period_start = klines[0]["timestamp"] if klines else ""
    period_end = klines[-1]["timestamp"] if klines else ""
    params_snapshot = dict(params)
    params_snapshot["_kwikquant"] = {**reproducibility, "warnings": warnings}

    trade_rows = []
    for t in trades:
        row = {
            "time": t.time,
            "side": t.side,
            # 金额一律过 norm(-0 纪律,spec §8;margin_used/funding_cum 之外的金额字段补齐)
            "price": str(norm(t.price)),
            "amount": str(norm(t.amount)),
            "fee": str(norm(t.fee)),
        }
        if t.position_effect is not None:
            row["position_effect"] = t.position_effect
        if t.liquidation:
            row["liquidation"] = True
        trade_rows.append(row)

    equity_rows = []
    for e in equity_curve:
        row = {"time": e["time"], "equity": str(norm(e["equity"]))}
        if "margin_used" in e:
            row["margin_used"] = str(norm(e["margin_used"]))
            row["funding_cum"] = str(norm(e["funding_cum"]))
        equity_rows.append(row)

    out = {
        "name": name,
        "params": params_snapshot,
        "symbol": symbol,
        "timeframe": timeframe,
        "period": {"start": str(period_start), "end": str(period_end)},
        "trades": trade_rows,
        "equity_curve": equity_rows,
        "metrics": {},  # Java PerformanceCalculator 重算
        "warnings": warnings,  # 诊断标注(拒单/强平/资金费代理/末根未撮合;类别与预算见 perp-backtest-spec §8)
    }
    if market_type == "PERP":
        # 强平近似模型强制声明(perp-backtest-spec §4.2,防研究侧误读保守偏差)
        out["market_type"] = "PERP"
        out["liquidation_model"] = "BAR_EXTREME_APPROX"
    return out
