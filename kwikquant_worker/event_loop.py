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
from collections import deque
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, replace
from decimal import Decimal, InvalidOperation
from typing import TYPE_CHECKING, Any

from kwikquant_worker import acceptance
from kwikquant_worker.backtest import matching
from kwikquant_worker.backtest.matching import MatchConfig
from kwikquant_worker.backtest.perp_ledger import FundingReplay, PerpLedger, norm, parse_instant
from kwikquant_worker.context import FillEvent, FundingEvent, LiquidationEvent, clamp_dust_close
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


def _dispatch_callback(callback, payload, ctx, *, where: str, at: str) -> None:
    """回测侧策略事件回调派发(on_fill/on_funding/on_liquidation,docs/strategy-api.md §8)。

    异常语义与 on_bar 同级 fail-fast:回调抛异常 → RuntimeError 炸整个任务(研究工具
    不允许带病出报告;runner 侧的"记 stderr 继续"差异见 RunnerEventLoop)。
    callback 为 None 时调用方直接跳过(payload 都不构造),本函数不做 None 判定。"""
    try:
        callback(payload, ctx)
    except Exception as e:  # noqa: BLE001 — 统一转 RuntimeError(与 on_bar 同型)
        raise RuntimeError(f"strategy {where} failed at {at}: {e!r}") from e


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
    """事件时间轴驱动 ``on_bar(bar, ctx)`` + 事件回调,汇总 trades + equity_curve 输出回测结果 JSON。

    时间轴 = BAR / FUNDING / EQUITY 节点按归属归并派发(:meth:`_timeline`,
    docs/perp-backtest-spec.md §5.1/§6)。BAR 节点处理包(PERP):强平(派发 on_liquidation)
    → acceptance → 撮合 → 账本闸门(逐笔成交派发 on_fill)→ on_bar;FUNDING 节点:资金费
    期次精确时间戳结算(mark 真值化,派发 on_funding);EQUITY 节点:权益记录(含本 bar 归属
    期次结算结果)。净持仓账本走 :class:`PerpLedger`(钱数学委托 perp_math 内核);需要
    ``pair_specs``(接受性快照)与 ``funding_periods``(已结算资金费序列,ASC)。
    SPOT 无 FUNDING 节点,BAR→EQUITY 顺序与逐 bar 驱动时代输出逐字节一致。
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
        task_end: str | None = None,
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
        # 任务快照终点(run 尾部漏期诊断用):K 线提前结束(actualEnd < end)时,落在最后一根
        # bar 归属窗之后、任务 end 之前的已结算期次永不参与回放——进 warnings 显性标注。
        # None(存量测试/直构形态)不检测。
        self._task_end = task_end
        # ctx.equity()/available_cash() 直读的引擎账本状态(run() 内推进;ctx.bind 后可见)
        self._cash: Decimal = initial_capital
        self._last_close: Decimal | None = None
        self._ledger: PerpLedger | None = None
        self._ctx: BacktestContext | None = None
        # 引擎内部成交序号(run() 重置;FillEvent.order_id 用——非平台订单 id,SPOT/PERP 统一计数)
        self._next_order_id: int = 1

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

    def run(
        self,
        on_bar,
        ctx: BacktestContext,
        klines: list[dict],
        *,
        on_fill=None,
        on_funding=None,
        on_liquidation=None,
    ) -> dict[str, Any]:
        """跑完整个事件时间轴。``on_fill``/``on_funding``/``on_liquidation`` 是策略可选
        事件回调(docs/strategy-api.md §8),None = 不派发(payload 都不构造,零开销)。"""
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
        self._next_order_id = 1

        # 事件时间轴(docs/perp-backtest-spec.md §6):BAR / FUNDING / EQUITY 节点按归属归并派发。
        # SPOT 无 FUNDING 节点,BAR→EQUITY 与逐 bar 驱动的输出顺序逐字节一致。
        for kind, i, payload in self._timeline(klines, replay):
            if kind == "bar":
                k = payload
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

                # NEXT_BAR 结构保证(spec §7):节点开头先 drain 上一节点排队的意图——本节点内
                # 派发的一切回调(on_liquidation/on_fill)与 on_bar 下的单都留在队列,最早下一根
                # bar 撮合。drain 若晚于强平段,on_liquidation 内下单会被本 bar 撮合段消费
                # (强平时点策略尚未"看到"本 bar,等价前视偏差)——位置即不变量。
                intents = ctx.take_pending()

                # 最新已收盘 close 在一切派发/撮合前落账本状态:回调与 on_bar 内调 ctx.equity()
                # 读到同一本 bar 口径,与 position().unrealized_pnl(视图 sync 同用本 bar close)
                # 恒等式 equity = cash + unrealized 成立(回测是事后模拟,bar OHLC 全已知)
                close_dec = Decimal(str(k["close"]))  # 原始 str 转,保精度
                self._last_close = close_dec

                if perp:
                    # PERP 撮合段(perp-backtest-spec §6 步骤 1-2):强平先于撮合 → acceptance →
                    # 撮合 → 账本闸门 → 内核应用(逐事件派发 on_liquidation / on_fill)
                    self._perp_liquidation_phase(ctx, ledger, bar, snapshot, trades, warnings, on_liquidation)
                    self._perp_match_intents(ctx, ledger, bar, snapshot, trades, warnings, on_fill, intents)
                else:
                    for intent in intents:
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
                                order_id=self._next_order_id,
                                symbol=intent.symbol,
                                side=intent.side,
                                price=fill.price,
                                qty=fill.qty,
                                fee=fill.fee,
                                fee_currency=fill.fee_currency or "",
                                filled_at=fill.filled_at,
                            )
                        )
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
                        if on_fill is not None:
                            # 账本应用后派发:回调内读 ctx.position() 已含本笔成交
                            _dispatch_callback(
                                on_fill,
                                FillEvent(
                                    symbol=intent.symbol,
                                    side=intent.side,
                                    price=fill.price,
                                    qty=fill.qty,
                                    fee=fill.fee,
                                    fee_currency=fill.fee_currency or "",
                                    filled_at=fill.filled_at or bar.timestamp,
                                    order_id=self._next_order_id,
                                    liquidity=fill.liquidity,
                                ),
                                ctx,
                                where="on_fill",
                                at=bar.timestamp,
                            )
                        self._next_order_id += 1

                try:
                    on_bar(bar, ctx)
                except Exception as e:
                    raise RuntimeError(f"strategy on_bar failed at {bar.timestamp}: {e!r}") from e

                # 进度上报(节流:每 PROGRESS_REPORT_EVERY bar 或末根;失败容错见 ctx.report_progress)
                if (i + 1) % PROGRESS_REPORT_EVERY == 0 or i == total - 1:
                    ctx.report_progress(i + 1, total)

            elif kind == "funding":
                # FUNDING 节点(perp-backtest-spec §5/§6 步骤 4):逐期结算 + 派发 on_funding。
                period = payload
                # flat 跳过:不结算不派发(§5.5——与 runner 侧"无结算落账行即无事件推送"同构)
                if ledger.is_flat():
                    continue
                # mark 真值化(§5.3):优先期次行交易所真值,行缺失或 ≤0(脏行/回填行防御,
                # 与 Java PaperFundingSettlementScheduler 的 signum<=0 守卫同纪律——0 静默
                # 零收费、负值符号翻转都是错收)视同缺失,fallback 归属 bar close
                # (self._last_close 已由紧邻在前的 BAR 节点更新 = 归属 bar close)
                mark = (
                    period.mark_price
                    if period.mark_price is not None and period.mark_price > 0
                    else self._last_close
                )
                amount = ledger.settle_funding_period(period.settled_rate, mark)
                self._sync_position_view(ctx, ledger, self._last_close)
                if on_funding is not None:
                    # Z 记法统一:datetime.isoformat() 输出 +00:00,与 kline timestamp/
                    # filled_at/runner WS settleTime 的 Z 记法混用会让策略的字符串对齐
                    # 静默失配(data_loader 同款 replace 纪律)
                    ts = period.funding_time.isoformat().replace("+00:00", "Z")
                    _dispatch_callback(
                        on_funding,
                        FundingEvent(
                            symbol=self.symbol,
                            funding_time=ts,
                            settled_rate=period.settled_rate,
                            amount=amount,
                            qty_at_settle=abs(ledger.pos.signed_qty),
                            mark_price=mark,
                            source=period.source,
                        ),
                        ctx,
                        where="on_funding",
                        at=ts,
                    )

            else:  # kind == "equity"
                # EQUITY 收尾节点(§6 步骤 5):排在本 bar 归属 FUNDING 节点之后——funding_cum/
                # margin_used 在本 bar 权益点即反映(口径与 bar 驱动时代一致,§5.2)
                k = payload
                ts = str(k["timestamp"])
                if perp:
                    close_dec = Decimal(str(k["close"]))
                    equity_curve.append(
                        {
                            "time": ts,
                            "equity": ledger.equity(close_dec),
                            "margin_used": ledger.margin_used,
                            "funding_cum": ledger.funding_cum,
                        }
                    )
                else:
                    equity_curve.append({"time": ts, "equity": self.current_equity()})

        leftover = len(ctx._pending)
        if leftover:
            warnings.append(f"末尾 bar 提交的 {leftover} 笔订单未参与撮合（回测区间已结束，NEXT_BAR 无下一根）")
        if perp:
            # 尾部漏期警示(spec §5"绝不静默漏收"的收尾防线):K 线提前结束时,任务 end 之前
            # 仍有未被任何 bar 归属窗消费的已结算期次 → 显性标注(funding_cum/equity 不含这些期)
            if self._task_end is not None:
                # 纯诊断功能不得炸掉已完整跑完的回测:task_end 不可解析(手拼 config/
                # 非 tz-aware ISO)时降级为警示并跳过检测,结果照常产出(生产路径 Jackson
                # Instant→ISO 恒合法,此为防御面)
                try:
                    end_dt = parse_instant(self._task_end)
                except (ValueError, TypeError) as e:
                    warnings.append(f"task_end {self._task_end!r} 不可解析（{e!r}），尾部漏期检测跳过")
                else:
                    stranded = [p for p in replay.pending_periods() if p.funding_time <= end_dt]
                    if stranded:
                        first_ts = stranded[0].funding_time.isoformat().replace("+00:00", "Z")
                        warnings.append(
                            f"K 线提前结束：{len(stranded)} 期已结算资金费未参与回放"
                            f"（首漏期 {first_ts}，其后无 bar 承载归属窗；funding_cum/权益曲线不含这些期次）"
                        )
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

    # ---------- 事件时间轴与 PERP 编排(docs/perp-backtest-spec.md §5/§6) ----------

    @staticmethod
    def _timeline(klines: list[dict], replay: "FundingReplay | None"):
        """事件时间轴生成器:BAR 节点 → 归属 FUNDING 节点 → EQUITY 收尾节点。

        FUNDING 归属由 :class:`FundingReplay` 游标保证((ts, ts+timeframe] 左开右闭 +
        断档 catch-up,spec §5.1)——精确 ``funding_time`` 落在归属 bar span 内,排序语义
        等价于"排在所有 open_time < T 的 BAR 之后、open_time ≥ T 的 BAR 之前"。
        SPOT(replay=None)只有 BAR→EQUITY 两种节点,输出顺序与逐 bar 驱动逐字节一致。
        """
        for i, k in enumerate(klines):
            yield ("bar", i, k)
            if replay is not None:
                for period in replay.periods_for_bar(str(k["timestamp"])):
                    yield ("funding", i, period)
            yield ("equity", i, k)

    def _perp_liquidation_phase(self, ctx, ledger, bar, snapshot, trades, warnings, on_liquidation) -> None:
        """强平判定与成交(先于本 bar 新订单撮合,spec §4.1 规则 4);成交后派发
        on_liquidation(§4 规则 6,不双派 on_fill——与 runner 通道互斥对齐)。"""
        liq = ledger.check_liquidation(
            timestamp=bar.timestamp,
            open_=Decimal(snapshot["open"]),
            high=Decimal(snapshot["high"]),
            low=Decimal(snapshot["low"]),
        )
        if liq is None:
            return
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
        # 持仓视图先刷新再派发:回调内读 ctx.position() 已是强平后的 flat 态
        self._sync_position_view(ctx, ledger, Decimal(snapshot["close"]))
        if on_liquidation is not None:
            _dispatch_callback(
                on_liquidation,
                LiquidationEvent(
                    symbol=self.symbol,
                    timestamp=liq.timestamp,
                    position_side=liq.position_side,
                    qty=liq.qty,
                    price=liq.price,
                    realized_pnl=liq.gross_pnl - liq.fee,
                    margin_mode=liq.margin_mode,
                ),
                ctx,
                where="on_liquidation",
                at=liq.timestamp,
            )

    def _perp_match_intents(self, ctx, ledger, bar, snapshot, trades, warnings, on_fill, intents) -> None:
        """意图撮合段:acceptance → 撮合 → 账本闸门 → 应用;逐笔成交后派发 on_fill(§6 步骤 2)。

        ``intents`` 是 BAR 节点开头 drain 的上一节点意图(不含本节点回调新下的单——
        NEXT_BAR 结构保证,见 run())。"""
        for intent in intents:
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
            # 持仓视图先刷新再派发:on_fill 回调内读 ctx.position() 已含本笔成交
            self._sync_position_view(ctx, ledger, Decimal(snapshot["close"]))
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
                        filled_at=fill.filled_at or bar.timestamp,
                        order_id=self._next_order_id,
                        liquidity=fill.liquidity,
                        position_effect=intent.position_effect,
                    ),
                    ctx,
                    where="on_fill",
                    at=bar.timestamp,
                )
            self._next_order_id += 1
        self._sync_position_view(ctx, ledger, Decimal(snapshot["close"]))

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


def _ws_dec(v) -> Decimal | None:
    """WS 金额字段防御转换:Java 侧 BigDecimal→JSON number(ws-contract 已知金额红线缺口),
    经 ``Decimal(str(v))`` 转,不参与 float 算术;stream 层 ``parse_float=Decimal`` 后
    JSON 小数直达 Decimal(str(float) 最短往返仅在旧形态 number 兜底时发生)。
    None/非法/非有限(NaN/Infinity 字面量防御)→ None(调用方按字段缺失处理)。"""
    if v is None:
        return None
    if isinstance(v, Decimal):
        return v if v.is_finite() else None
    try:
        d = Decimal(str(v))
    except InvalidOperation:
        return None
    return d if d.is_finite() else None


def _fill_event_from_ws(payload: dict) -> FillEvent | None:
    """WS FillEvent 载荷(ws-contract 3.4)→ 契约 :class:`FillEvent`;必填字段缺失/非法返 None。"""
    price, qty, fee = _ws_dec(payload.get("price")), _ws_dec(payload.get("qty")), _ws_dec(payload.get("fee"))
    if payload.get("symbol") is None or payload.get("side") is None or price is None or qty is None or fee is None:
        return None
    order_id = payload.get("orderId")
    try:
        oid = int(order_id) if order_id is not None else None
    except (TypeError, ValueError):
        oid = None
    return FillEvent(
        symbol=str(payload["symbol"]),
        side=str(payload["side"]),
        price=price,
        qty=qty,
        fee=fee,
        fee_currency=str(payload.get("feeCurrency") or ""),
        filled_at=str(payload.get("filledAt") or ""),
        order_id=oid,
        liquidity=payload.get("liquidity"),
        position_effect=payload.get("positionEffect"),
    )


def _liquidation_event_from_ws(payload: dict) -> LiquidationEvent | None:
    """WS LiquidationEvent 载荷(ws-contract 3.9)→ 契约 :class:`LiquidationEvent`。"""
    qty = _ws_dec(payload.get("qty"))
    if payload.get("symbol") is None or payload.get("positionSide") is None or qty is None:
        return None
    return LiquidationEvent(
        symbol=str(payload["symbol"]),
        timestamp=str(payload.get("timestamp") or ""),
        position_side=str(payload["positionSide"]),
        qty=qty,
        price=_ws_dec(payload.get("liquidationPrice")),
        realized_pnl=_ws_dec(payload.get("realizedPnl")),
        margin_mode=payload.get("marginMode"),
        reason=payload.get("reason"),
    )


def _funding_event_from_ws(payload: dict) -> FundingEvent | None:
    """WS FundingSettlementEvent 载荷(ws-contract 3.10)→ 契约 :class:`FundingEvent`。

    WS 载荷无 mark_price/source 字段(契约恒 None,docs/strategy-api.md §8 矩阵声明)。"""
    amount = _ws_dec(payload.get("fundingAmount"))
    if payload.get("symbol") is None or amount is None:
        return None
    return FundingEvent(
        symbol=str(payload["symbol"]),
        funding_time=str(payload.get("settleTime") or ""),
        settled_rate=_ws_dec(payload.get("fundingRate")),
        amount=amount,
        qty_at_settle=_ws_dec(payload.get("qtyAtSettle")) or Decimal(0),
        mark_price=None,
        source=None,
    )


# ---------- on_fill 断线增量补拉(GET /api/v1/worker/fills-since) ----------

CATCHUP_INTERVAL_S = 60.0  # 轮询周期:补拉事件延迟上界 = 周期 + 服务端提交安全边界(2s)
CATCHUP_OVERLAP = 100  # 每轮回拉重叠窗口(id 数):兜 BIGSERIAL 分配序≠提交序的"晚提交小 id"洞
CATCHUP_PAGE_LIMIT = 200  # 服务端单页上限(WorkerFillCatchupController.MAX_LIMIT)
CATCHUP_SEEN_MAX = 4096  # fillId 去重集容量(FIFO 淘汰;不变量前提与停摆例外见 mark_seen/_fill_catchup_loop)
# 停摆恢复防护阈值:游标停摆期间直播认领数达去重集容量一半 → 放弃窗口重播种(见 _fill_catchup_loop;
# 半容量留 2x 裕度——严格危险条件是"窗口内首次认领总量 ≥ 容量",防护以直播认领为主判据,
# 覆盖停摆主触发角;漏发主导的复合角(REST 停摆 × WS 半退化漏发交叠)残余一次性有界重复面,
# 需三重复合极端故障且主触发面已被 V63 索引移除,TD 备案)
CATCHUP_STALL_RESEED_MIN = CATCHUP_SEEN_MAX // 2


class FillCatchup:
    """runner 断线窗口 on_fill 事件增量补拉器(worker 内部组件,非 SDK 公开面)。

    WS broker 不持久化离线消息(docs/ws-contract.md §6):断线窗口内推送的事件即丢。本组件经
    GET /api/v1/worker/fills-since(RUNNER token,账户由 token 绑定在服务端收口,worker 不传
    accountId)按 fillId 游标增量补拉绑定账户的成交行,on_fill 通道因此获得**进程生命周期内
    exactly-once** 派发兜底。on_funding/on_liquidation 无补拉通道(资金费低频且结算正确性不
    依赖事件送达;强平行被服务端按通道互斥契约排除,不得派发成 on_fill)——断线窗口丢失仍归
    ctx.position()/REST 对账契约(docs/strategy-api.md §8)。

    一致性分工:服务端 created_at 安全边界(滞后 2s,DB 单时钟)保证只回已提交可见行;worker 侧
    重叠重拉(CATCHUP_OVERLAP)+ fillId 去重兜住分配序≠提交序的洞与 WS/补拉双流交叠。
    重启不回放:播种取"当前安全尾部 id",进程重启窗口的事件缺口归对账契约。
    停摆防护:补拉游标停摆(REST 故障)而直播照常派发时,去重集 FIFO 淘汰与恢复后的升序
    重扫会**锁步**(每次"首次认领"恰淘汰尚未扫到的直播前沿 id,抑制率归零→全窗口重复
    派发)——补拉循环在轮内认领数 ≥ CATCHUP_STALL_RESEED_MIN 时放弃该窗口并重播种
    (WARN 出声,窗口缺口归对账契约,与"重启不回放"同款语义,见 _fill_catchup_loop)。

    线程契约:seed/fetch_page 是同步 HTTP(asyncio.to_thread 工作线程调用,只读 self._client);
    mark_seen 仅在 asyncio loop 线程调用(_on_fill_ws 入口)——去重集无跨线程变更;游标由
    RunnerEventLoop 的补拉循环持有(同在 loop 线程)。
    """

    def __init__(self, client) -> None:
        self._client = client
        self._seen_ids: set[int] = set()
        self._seen_order: deque[int] = deque()

    def seed(self) -> int:
        """播种:返回绑定账户当前安全尾部游标(afterId 缺省 = 服务端回空行 + 尾部 id)。

        响应缺 cursor 键(服务端契约破坏)→ raise 进播种重试分支,**绝不回退 0**——
        未播种游标会回放账户全部成交历史(与循环侧红线同源)。fetch_page 的缺键回显
        afterId 是安全方向(游标停滞不冒进),与此处不对称是有意的。"""
        data = self._client.get("/api/v1/worker/fills-since")
        if "cursor" not in data:
            raise ValueError("fills-since seed response missing 'cursor'")
        return int(data["cursor"])

    def fetch_page(self, after_id: int) -> tuple[list[dict], int]:
        """拉一页:返回 (成交行, 推荐游标)——页非空 = 页内最后一行 id,空页 = 服务端回显 afterId。"""
        data = self._client.get(
            "/api/v1/worker/fills-since", params={"afterId": after_id, "limit": CATCHUP_PAGE_LIMIT}
        )
        rows = data.get("fills") or []
        return list(rows), int(data.get("cursor", after_id))

    def mark_seen(self, fill_id: int) -> bool:
        """登记 fillId;False = 已处理过(重复,跳过派发),True = 首次(认领)。

        容量 FIFO 淘汰的无重复前提 = **重扫窗有界**(游标每轮正常前进时,重扫窗只有
        overlap+轮间新增,远小于容量)。游标停摆时窗口可无界增长,升序重扫与 FIFO 淘汰
        锁步(认领淘汰的恰是尚未扫到的前沿 id)——该状态由补拉循环的
        CATCHUP_STALL_RESEED_MIN 防护检测并放弃窗口重播种(见 _fill_catchup_loop),
        淘汰机制自身不承担停摆窗的去重责任。"""
        if fill_id in self._seen_ids:
            return False
        self._seen_ids.add(fill_id)
        self._seen_order.append(fill_id)
        while len(self._seen_order) > CATCHUP_SEEN_MAX:
            self._seen_ids.discard(self._seen_order.popleft())
        return True


class RunnerEventLoop:
    """模拟盘/实盘长驻循环 — StreamClient 订阅 /topic/kline → bar 关闭检测 → on_bar(bar, ctx)。

    与回测 BacktestEventLoop 对偶:回测按事件时间轴喂历史(on_bar 每根 + 事件回调节点内
    **同步有序**派发),实盘 WS 推 kline 实时更新(尾根替换),runner 做 bar 关闭检测
    (openTime 变化=前一根关闭→用前一根调 on_bar)。函数式 on_bar(bar, ctx) 与回测统一
    (用户一份策略通吃回测+live)。

    事件回调(docs/strategy-api.md §8):策略定义 on_fill/on_funding/on_liquidation 时按需
    订阅对应 user 级 topic(/topic/fills|liquidations|funding/{userId},user_id 由 bootstrap
    下发;on_funding/on_liquidation 仅 PERP 订阅)。user 级 topic 覆盖该用户**全部账户**
    (PAPER/LIVE 多账户并存是产品常态)——派发前按绑定 accountId+marketType+symbol 过滤
    (accountId 由 bootstrap 下发),防跨账户事件泄漏进策略回调(模拟盘/实盘强区分红线)
    与同账户 SPOT/PERP 同 symbol 串扰。**到达顺序无保证**
    (跨 topic fanout 无序,ws-contract §6)——与回测"节点内同步有序"是有意差异,策略不得
    依赖回调与 on_bar 的相对顺序;但派发**串行且同一工作线程**(单线程 executor,结构保证
    ——策略模块级状态无并发交错,与回测单线程语义对齐)。**断线窗口的事件**:on_fill 由
    周期性增量补拉兜底(:class:`FillCatchup`,fillId 去重、进程内 exactly-once,延迟上界 =
    轮询周期 + 服务端提交安全边界;**重启不回放**);on_funding/on_liquidation 仍永久丢失
    (无补拉通道)——自维护状态须周期性以 ctx.position()/REST 对账兜底。
    止损止盈靠交易所条件单(OKX stop-limit/OCO,on_bar 内 ctx.place_order 下条件单),
    不依赖 on_tick。
    """

    def __init__(self, health_signals: "HealthSignals | None" = None) -> None:
        self._current_bar: Bar | None = None
        self._on_bar = None
        self._on_fill = None
        self._on_funding = None
        self._on_liquidation = None
        self._fill_catchup: FillCatchup | None = None
        # 自上次成功 drain 以来的去重认领计数(直播+补拉共用,asyncio loop 线程独占变更):
        # 停摆恢复防护的判据(见 _fill_catchup_loop 的 CATCHUP_STALL_RESEED_MIN 分支)
        self._catchup_claims = 0
        self._ctx = None
        self._signals = health_signals
        self._symbol = ""
        self._market_type = ""
        self._account_id: int | None = None
        # 策略调用专用单线程 executor:on_bar 与事件回调**串行 + 同一工作线程**(结构保证,
        # 与回测单线程语义对齐——策略模块级状态无需加锁,线程身份稳定 threading.local 可用)。
        # 到达顺序仍无保证(跨 topic fanout,docs/strategy-api.md §8);串行指不并发交错。
        self._executor = ThreadPoolExecutor(max_workers=1, thread_name_prefix="kq-strategy")
        # 过滤失配限次出声(user 级 topic 推该用户全部账户/全部标的事件,多账户/多策略
        # 并存时不匹配是常态,防刷屏);按 通道+过滤键 分桶——单计数共享会让任一桶触发
        # 限额后其余失配永久静默
        self._mismatch_logged: dict[str, int] = {}

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
        user_id: int | None = None,
        account_id: int | None = None,
        on_fill=None,
        on_funding=None,
        on_liquidation=None,
        fill_catchup: FillCatchup | None = None,
    ) -> None:
        """注册 kline handler → asyncio.run(WS 主通道 + on_fill 补拉通道) 长驻。

        exchange/market_type/symbol/interval 用于 on_kline topic 订阅(对齐后端 KLINE_TOPIC_FORMAT)。
        user_id + on_* 事件回调:按需订阅 user 级事件 topic(docs/ws-contract.md §5)——
        未定义的回调不订阅(省后端 fanout);user_id 缺失(旧 bootstrap)时事件 topic 一律
        不订阅,on_bar 主通道照常(事件回调静默不派发,启动日志可观测)。
        account_id:绑定账户(bootstrap 下发)——user 级 topic 覆盖该用户全部账户,派发前按
        accountId 过滤防 PAPER/LIVE 跨账户泄漏;缺失(旧 bootstrap)时降级仅 symbol/市场类型
        过滤并出声(跨账户事件可能进回调,可观测不静默)。
        fill_catchup:on_fill 断线补拉器(worker_server 在定义 on_fill 且 bootstrap 带
        userId 时装配传入——userId 缺失 = 旧 bootstrap,补拉端点必然同样不存在,事件通道
        整体不启用)——周期增量补拉断线窗口成交行,与 WS 事件同链路派发(fillId 去重);
        None = 不启用补拉(on_funding/on_liquidation 无补拉通道,断线丢失语义见类 docstring)。
        """
        self._on_bar = on_bar
        self._ctx = ctx
        self._symbol = symbol
        self._market_type = market_type
        self._account_id = None if account_id is None else int(account_id)
        self._on_fill = on_fill
        self._on_funding = on_funding
        self._on_liquidation = on_liquidation
        self._fill_catchup = fill_catchup
        self._current_bar = None
        stream_client.on_kline(exchange, market_type, symbol, interval, self._on_kline)
        # 订阅 /topic/ticker 触发后端 onWsSubscribe 起 ticker worker(WS 驱动 persistent=false)。
        # PAPER 撮合靠 PaperExecutor.onTicker(ticker push);非 persistent 币的 ticker worker
        # 不订阅就不跑 → 撮合不发生。runner 自身不消费 ticker(策略用 on_bar),_on_tick 是 no-op。
        stream_client.on_tick(exchange, market_type, symbol, self._on_tick)
        if user_id is not None:
            if on_fill is not None:
                stream_client.on_fill(int(user_id), self._on_fill_ws)
            if on_liquidation is not None:
                if market_type == "PERP":
                    stream_client.on_liquidation(int(user_id), self._on_liquidation_ws)
                else:
                    # SPOT 无强平事件(Java 只对 PERP 持仓广播):不白订,出声防作者误解
                    # (与 on_funding 同款纪律,也与回测路径 SPOT 提示一致)
                    print(
                        "[runner] SPOT strategy has no liquidation events: "
                        "on_liquidation defined but never dispatched",
                        file=sys.stderr,
                    )
            if on_funding is not None:
                if market_type == "PERP":
                    stream_client.on_funding(int(user_id), self._on_funding_ws)
                else:
                    # SPOT 无资金费事件(Java 只对 PERP 结算推送):不白订,出声防作者误解
                    print(
                        "[runner] SPOT strategy has no funding events: "
                        "on_funding defined but never dispatched",
                        file=sys.stderr,
                    )
            if account_id is None and (
                on_fill is not None or on_funding is not None or on_liquidation is not None
            ):
                # 旧 bootstrap 无 accountId:降级仅市场类型/symbol 过滤——同用户其他账户
                # (PAPER/LIVE)的事件会进回调,出声让降级可观测(不静默破坏账户隔离)
                print(
                    "[runner] accountId missing from bootstrap; event callbacks filter "
                    "without account scope — events from other accounts of this user may leak in",
                    file=sys.stderr,
                )
        elif on_fill is not None or on_funding is not None or on_liquidation is not None:
            print(
                "[runner] event callbacks defined but userId missing from bootstrap; "
                "event topics not subscribed (on_bar unaffected)",
                file=sys.stderr,
            )
        try:
            asyncio.run(self._run_streams(stream_client))
        except KeyboardInterrupt:
            pass
        finally:
            self._executor.shutdown(wait=False)

    async def _run_streams(self, stream_client) -> None:
        """WS 主通道 + on_fill 断线补拉通道并行(补拉仅在定义 on_fill 且装配补拉器时启用)。

        stream_client.run 自带指数退避重连不主动抛;补拉循环失败自兜(下轮重试)——两任务
        常态下都不结束;SIGTERM → asyncio.run 取消全部任务(CancelledError 透传,不重连)。"""
        if self._fill_catchup is not None and self._on_fill is not None:
            await asyncio.gather(stream_client.run(), self._fill_catchup_loop())
        else:
            await stream_client.run()

    async def _fill_catchup_loop(self) -> None:
        """on_fill 断线增量补拉循环(周期轮询,非"重连 hook")。

        为何周期轮询而非重连 hook:TCP 半开的静默断线要等 websockets 库 ping 超时才触发
        重连,重连 hook 覆盖不了"连接活着但 broker 丢消息"的窗口;周期补拉把事件延迟上界
        钉在轮询周期(CATCHUP_INTERVAL_S)+ 服务端提交安全边界(2s),并天然覆盖重连场景。
        失败只出声下轮重试——补拉通道是 WS 主通道的兜底,任何故障都不得杀长驻进程。

        停摆防护:游标停摆(drain 持续失败)期间直播流持续认领成交——轮内认领 ≥
        CATCHUP_STALL_RESEED_MIN(去重集半容量)时,恢复后对本窗口的升序重扫与 FIFO 淘汰
        锁步 → 全量重复派发(机理见 mark_seen docstring),此时放弃窗口并重播种(cursor/
        floor 同抬到本轮尾部,WARN 出声),窗口缺口归对账契约。阈值条件含直播认领数:
        纯行数阈值会误杀"WS 断很久、REST 健康"场景(认领=0,应照常补发——补拉核心价值)。

        播种:先拉"当前安全尾部 id"作初始游标(重启不回放历史事件——重启窗口的事件缺口归
        ctx.position()/REST 对账契约,与 _prefill_history 只回填 bar 不回填事件同口径);
        播种失败持续重试,**绝不用未播种游标拉取**(游标 0 会回放账户全部成交历史)。播种值
        同时是 overlap 回拉的**下界(floor)**:floor 之下是启动前历史成交,补拉任何轮次都
        不越过 floor 回拉——否则每次启动首轮就会把至多 OVERLAP 笔历史事件回放成 on_fill
        ("重启不回放"系统性违约,历史 filled_at 可为数月前,而 on_fill 的文档用途含触发
        下单)。已知可忽略边界:created_at 落在播种安全边界(≤2s)内的成交 id 在 floor 之上,
        首轮会派发——前一进程临终尾行,量级 ≤2s,属"断线窗口"而非历史回放。"""
        catchup = self._fill_catchup
        assert catchup is not None
        cursor: int | None = None
        floor = 0  # 播种下界:overlap 回拉不越界(seed 成功前不 drain,初值不被消费)
        while True:
            if cursor is None:
                try:
                    cursor = await asyncio.to_thread(catchup.seed)
                    floor = cursor
                except Exception as e:  # noqa: BLE001 — Java 未就绪(部署/重启窗口),下轮重试
                    print(f"[runner] fill catchup seed failed (retry next tick): {e!r}", file=sys.stderr)
            else:
                try:
                    rows, new_cursor = await self._catchup_drain(cursor, floor)
                except Exception as e:  # noqa: BLE001 — 补拉失败不影响 WS 主通道,下轮重试
                    print(f"[runner] fill catchup failed (retry next tick): {e!r}", file=sys.stderr)
                else:
                    if rows and self._catchup_claims >= CATCHUP_STALL_RESEED_MIN:
                        # 停摆恢复防护(锁步机理见 mark_seen/FillCatchup docstring):游标
                        # 停摆期间直播认领已达去重集半容量 → 本窗口升序重扫必然全量重复
                        # 派发,放弃窗口并重播种(floor 同步抬升,防 overlap 回拉再入窗),
                        # 窗口缺口归 ctx.position()/REST 对账契约——"重启不回放"同款语义
                        print(
                            f"[runner] fill catchup stalled while {self._catchup_claims} fills "
                            f"dispatched live (>= {CATCHUP_STALL_RESEED_MIN}); dropping "
                            f"{len(rows)} backfilled rows and reseeding — reconcile the "
                            f"window via ctx.position()/REST",
                            file=sys.stderr,
                        )
                        cursor = max(cursor, new_cursor)
                        floor = cursor
                    else:
                        for row in rows:
                            # 与 WS 事件同一派发链路(账户/市场类型/symbol 过滤 → convert →
                            # fillId 去重认领 → 串行 executor):补拉行与 WS FillEvent 载荷键
                            # 除 eventType 外同构(FillCatchupDto 对齐 ws-contract 3.4,金额为
                            # decimal string,_ws_dec 双形态兼容)。单行异常隔离(stderr 出声
                            # 继续下一行,游标照常推进——毒行卡页会让补拉通道永久停摆,兜底
                            # 通道宁可丢病态单行不可全局停滞);认领前抛出的毒行在滑出重叠
                            # 窗口前每轮重试:瞬时异常自愈,持续毒行窗内有界出声后随游标滑过
                            try:
                                await self._on_fill_ws(row)
                            except Exception as e:  # noqa: BLE001
                                print(
                                    f"[runner] catchup dispatch failed for fillId {row.get('fillId')!r}: {e!r}",
                                    file=sys.stderr,
                                )
                        cursor = max(cursor, new_cursor)
                    self._catchup_claims = 0
            await asyncio.sleep(CATCHUP_INTERVAL_S)

    async def _catchup_drain(self, cursor: int, floor: int) -> tuple[list[dict], int]:
        """拉齐 (max(floor, cursor-overlap), 安全尾部] 区间全部新行(翻页至 drained),
        返回 (行, 推荐游标)。

        overlap 回拉:BIGSERIAL 分配序 ≠ 提交序(并发事务 id/created_at 可交叉),服务端 2s
        安全边界只是第一道防线——每轮多回拉 CATCHUP_OVERLAP 个 id,让"晚提交但 id 更小"的
        洞行在后续轮次被重新检视;fillId 去重保证重叠窗口不重复派发(前提:成交写入事务
        秒级提交、洞宽 < 重叠窗口,ExecutionService/LiquidationService 均为短事务)。
        下界钉在播种 floor:floor 之下是启动前历史成交,永不回拉("重启不回放"契约,
        见 _fill_catchup_loop docstring)——overlap 只服务进程存活期内的晚提交洞。"""
        catchup = self._fill_catchup
        assert catchup is not None
        rows_all: list[dict] = []
        after = max(floor, cursor - CATCHUP_OVERLAP)
        new_cursor = cursor
        while True:
            rows, page_cursor = await asyncio.to_thread(catchup.fetch_page, after)
            rows_all.extend(rows)
            if page_cursor > new_cursor:
                new_cursor = page_cursor
            # 短页 = 拉齐;page_cursor 未前进 = 防御性退出(服务端空页回显 afterId,防死循环)
            if len(rows) < CATCHUP_PAGE_LIMIT or page_cursor <= after:
                break
            after = page_cursor
        return rows_all, new_cursor

    def _touch_ws(self) -> None:
        if self._signals is not None:
            self._signals.touch_ws_msg()

    def _touch_bar(self) -> None:
        if self._signals is not None:
            self._signals.touch_bar()

    def _record_on_bar(self, *, ok: bool) -> None:
        if self._signals is not None:
            self._signals.record_on_bar_outcome(ok=ok)

    def _record_callback(self, *, ok: bool) -> None:
        if self._signals is not None:
            self._signals.record_callback_outcome(ok=ok)

    def _on_tick(self, payload: dict) -> None:
        """ticker 回调 — no-op。订阅 /topic/ticker 仅为触发后端起 ticker worker(WS 驱动),
        让 PaperExecutor.onTicker 收到 ticker push 完成撮合。runner 自身不消费 ticker(策略用 on_bar)。
        """
        return

    async def _on_kline(self, payload: dict) -> None:
        """bar 关闭检测:openTime 前进=前一根关闭 → on_bar(前一根)+ set_bar。

        首根只缓存;同 openTime 更新覆盖;倒退忽略。on_bar 在专用单线程 executor 跑
        (同步 place_order HTTP 阻塞线程不阻塞 event loop;支持 1m bar,WS 心跳不被卡;
        与事件回调串行同线程,见 __init__ 注释)。
        """
        self._touch_ws()
        bar = _bar_from_kline(payload)
        if self._current_bar is None:
            self._current_bar = bar
            return
        if bar.timestamp > self._current_bar.timestamp:
            closed = self._current_bar
            self._current_bar = bar  # 新 bar(未关闭)
            # 单线程 executor: on_bar 含同步 place_order HTTP,阻塞线程不阻塞 event loop;
            # 与事件回调串行同线程(__init__ 注释,策略状态无并发交错)
            await asyncio.get_running_loop().run_in_executor(self._executor, self._invoke_on_bar, closed)
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

    # ---------- 事件回调派发(docs/strategy-api.md §8) ----------

    async def _on_fill_ws(self, payload: dict) -> None:
        # 市场类型过滤:同一账户 SPOT+PERP 可同 symbol 并存、fills topic 两路共用——
        # 只派发本策略市场类型的成交。payload 缺 marketType(旧后端版本偏斜)时跳过该层
        # 过滤(liquidation/funding 通道天然 PERP-only,无需此过滤)。
        mt = payload.get("marketType")
        if mt is not None and self._market_type and str(mt) != self._market_type:
            self._skip_limited(
                "on_fill:marketType",
                f"[runner] on_fill event with marketType {mt!r} skipped "
                f"(bound market type {self._market_type!r})",
            )
            return
        await self._dispatch_ws_event(
            payload,
            _fill_event_from_ws,
            self._on_fill,
            "on_fill",
            dedup_key=self._fill_dedup_key(payload),
        )

    def _fill_dedup_key(self, payload: dict) -> int | None:
        """on_fill 去重键(fillId,仅补拉启用时非 None)。

        WS 直播流 × 补拉流存在交叠窗口(同一成交可能两路到达)——去重保证进程内
        exactly-once。键的**认领**发生在过滤+转换成功之后、派发之前(_dispatch_ws_event
        内):入口处登记会让被过滤行(同用户其他账户/标的高频成交)冲刷有界去重集,畸形
        直播载荷会吞掉 fillId 压制完好的补拉行——两者都破坏 exactly-once。
        payload 缺 fillId(旧后端版本偏斜)→ None = 不去重照常派发:缺去重键不等于该丢
        事件(去重是补拉的兜底面,不是派发的前置条件)。"""
        if self._fill_catchup is None:
            return None
        raw = payload.get("fillId")
        try:
            return None if raw is None else int(raw)
        except (TypeError, ValueError):
            return None

    async def _on_liquidation_ws(self, payload: dict) -> None:
        await self._dispatch_ws_event(payload, _liquidation_event_from_ws, self._on_liquidation, "on_liquidation")

    async def _on_funding_ws(self, payload: dict) -> None:
        await self._dispatch_ws_event(payload, _funding_event_from_ws, self._on_funding, "on_funding")

    def _skip_limited(self, bucket: str, message: str) -> None:
        """过滤失配出声(每 通道+过滤键 桶至多 3 次):user 级 topic 推该用户全部账户/
        全部标的事件,多账户/多策略并存时失配是常态,防刷屏;但持续失配意味着绑定/数据
        口径错位,全静默不可观测——限次而非静默。"""
        counted = self._mismatch_logged.get(bucket, 0)
        if counted < 3:
            self._mismatch_logged[bucket] = counted + 1
            print(message, file=sys.stderr)

    async def _dispatch_ws_event(self, payload, convert, callback, where: str, *, dedup_key: int | None = None) -> None:
        """WS 事件 → 账户/symbol 过滤 → payload 转换 → 去重认领 → 单线程 executor 派发。

        **不 touch lastWsMsgAt**:探活语义是"行情流(kline)是否在流"(Java 编排器据此
        restart)——user 事件流量刷新它会在 kline worker 死亡而 fills/funding 仍在推时
        掩盖停摆(on_bar 不再触发却探活恒绿,restart 自愈通道被掐断)。
        账户过滤:user 级 topic 覆盖该用户全部账户(PAPER/LIVE 多账户并存是产品常态),
        非绑定账户的事件不派发(模拟盘/实盘强区分红线);payload 缺 accountId(旧后端
        版本偏斜)时跳过该层过滤,不 fail-closed 断整个回调通道。
        symbol 不匹配不派发(只派发绑定 symbol;两侧都是 canonical 形态);
        失配按桶限次出声(见 _skip_limited);payload 非法记 stderr 跳过(单条坏事件
        不杀长驻进程);回调异常记 stderr 继续(与 on_bar 同语义,docs/strategy-api.md §6 矩阵)。"""
        if callback is None:
            return
        event_account = payload.get("accountId")
        if (
            self._account_id is not None
            and event_account is not None
            and int(event_account) != self._account_id
        ):
            self._skip_limited(
                f"{where}:account",
                f"[runner] {where} event for account {event_account!r} skipped "
                f"(bound account {self._account_id!r})",
            )
            return
        if str(payload.get("symbol")) != self._symbol:
            self._skip_limited(
                f"{where}:symbol",
                f"[runner] {where} event for {payload.get('symbol')!r} skipped "
                f"(bound symbol {self._symbol!r})",
            )
            return
        event = convert(payload)
        if event is None:
            print(f"[runner] malformed {where} payload skipped: {payload!r}", file=sys.stderr)
            return
        if dedup_key is not None and self._fill_catchup is not None:
            # convert 成功才认领去重键(见 _fill_dedup_key):畸形直播载荷不吞 fillId——
            # 补拉行走 REST 独立序列化可能完好,提前认领会把它压制成进程内永久丢失
            # (exactly-once 退化成 at-most-zero)。认领与派发调度间无 await 间隙,
            # 直播×补拉交叠的第二次进入在此被拦下
            if not self._fill_catchup.mark_seen(dedup_key):
                return
            # 停摆防护计数(见 _fill_catchup_loop 的 CATCHUP_STALL_RESEED_MIN 分支)
            self._catchup_claims += 1
        # 单线程 executor:回调可能含同步 place_order HTTP,不阻塞 asyncio event loop;
        # 与 on_bar 串行同线程(策略状态无并发交错)
        await asyncio.get_running_loop().run_in_executor(self._executor, self._invoke_callback, callback, event, where)

    def _invoke_callback(self, callback, event, where: str) -> None:
        """同步调事件回调。异常容错:记 stderr 继续(与 on_bar 同级语义)。

        失败走**独立**健康计数 consecutiveCallbackFailures(不并入驱动 restart 的
        degraded 判定):事件是一次性 WS 推送、restart 后不重放——单次瞬时回调异常
        触发 restart 纯丢策略内存态无收益;低频回调(funding 8h 一期)的 degraded
        窗口远超 Java 探活阈值(30s×2),会把 restart 变成常态;策略代码 bug 导致的
        持续回调失败 restart 也修不好。计数仅供 /health 观测,restart 通道仍只由
        on_bar 持续失败驱动(on_bar 每 bar 必调,持续失败=策略失能,restart 语义成立)。"""
        assert self._ctx is not None
        try:
            callback(event, self._ctx)
        except Exception as e:  # noqa: BLE001
            self._record_callback(ok=False)
            print(f"[runner] {where} raised: {e!r}", file=sys.stderr)
        else:
            self._record_callback(ok=True)


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
