"""EventLoop — 回测 / Runner 事件驱动。

函数式:策略是顶层 ``def on_bar(bar, ctx):``,event_loop 逐 bar set klines+index
→ 先**本地撮合**上一 bar 产生的订单意图(Wave 2.2,``backtest/matching.py``,零 HTTP)
→ 调 ``on_bar(bar, ctx)`` → 维护 cash/equity(Decimal)→ 汇总回测结果 JSON。
行情(bar.open/close…)用 float 给用户;内部金额(cash/equity/holdings)用 Decimal,
从 k 原始 str 转(不绕 float,保精度)。

撮合语义单一真相源:``docs/matching-spec.md``(含 NEXT_BAR 时序与账本闸门,§7)。
"""

from __future__ import annotations

import asyncio
import logging
import sys
from dataclasses import dataclass
from decimal import Decimal
from typing import TYPE_CHECKING, Any

from kwikquant_worker.backtest import matching
from kwikquant_worker.backtest.matching import MatchConfig
from kwikquant_worker.strategy import Bar, BacktestContext, Fill

if TYPE_CHECKING:
    from kwikquant_worker.health_signals import HealthSignals

log = logging.getLogger(__name__)

# 逐 bar 进度上报节流间隔:每 N bar 上报一次(8760 bar → ~44 次 HTTP,开销可接受)。
# 末根 bar 强制上报,保证最终 100%。上报走已有 service_token HTTP 通道(同 place_order)。
PROGRESS_REPORT_EVERY = 200


@dataclass
class _TradeRecord:
    time: str
    side: str
    price: Decimal
    amount: Decimal
    fee: Decimal


class BacktestEventLoop:
    """逐 bar 驱动 ``on_bar(bar, ctx)``,汇总 trades + equity_curve 输出回测结果 JSON。"""

    def __init__(
        self,
        *,
        initial_capital: Decimal = Decimal("100000"),
        symbol: str = "",
        timeframe: str = "",
        params: dict[str, Any] | None = None,
        reproducibility: dict[str, Any] | None = None,
        matching_config: dict[str, Any] | None = None,
    ) -> None:
        self.initial_capital = initial_capital
        self.symbol = symbol
        self.timeframe = timeframe
        self.params = params or {}
        self.reproducibility = reproducibility or {}
        # 本地撮合配置(Java Gateway 下发快照);缺省 MatchConfig.defaults()(spec §2 两侧一致)
        self.match_config = MatchConfig.from_dict(matching_config)

    def run(self, on_bar, ctx: BacktestContext, klines: list[dict]) -> dict[str, Any]:
        if not isinstance(ctx, BacktestContext):
            raise TypeError("BacktestEventLoop requires ctx to be BacktestContext")

        ctx.set_klines(klines)
        trades: list[_TradeRecord] = []
        equity_curve: list[dict] = []
        warnings: list[str] = []
        cash = self.initial_capital
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
            for intent in ctx.take_pending():
                fill = matching.match(intent, snapshot, self.match_config)
                if fill is None:
                    if len(warnings) < 10:
                        warnings.append(
                            f"place_order returned None at {bar.timestamp} "
                            f"({intent.order_type}/{intent.side})"
                        )
                    continue
                # 账本闸门(原 Java 回测账本 canApply 语义,spec §7):BUY 现金足 / SELL 持仓足
                if intent.side == "BUY" and cash < fill.price * fill.qty + fill.fee:
                    log.warning("[event_loop] order rejected (insufficient cash) at %s", bar.timestamp)
                    if len(warnings) < 10:
                        warnings.append(
                            f"order rejected (insufficient cash) at {bar.timestamp} "
                            f"({intent.order_type}/{intent.side})"
                        )
                    continue
                if intent.side == "SELL" and ctx.position(intent.symbol).qty < fill.qty:
                    log.warning("[event_loop] order rejected (insufficient inventory) at %s", bar.timestamp)
                    if len(warnings) < 10:
                        warnings.append(
                            f"order rejected (insufficient inventory) at {bar.timestamp} "
                            f"({intent.order_type}/{intent.side})"
                        )
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
                cash = cash - signed * fill.price - fill.fee
                trades.append(
                    _TradeRecord(
                        time=fill.filled_at or bar.timestamp,
                        side=intent.side.lower(),
                        price=fill.price,
                        amount=fill.qty,
                        fee=fill.fee,
                    )
                )

            try:
                on_bar(bar, ctx)
            except Exception as e:
                raise RuntimeError(f"strategy on_bar failed at {bar.timestamp}: {e!r}") from e

            pos = ctx.position(self.symbol) if self.symbol else None
            close_dec = Decimal(str(k["close"]))  # 原始 str 转,保精度
            holdings_value = (pos.qty * close_dec) if pos and pos.qty != 0 else Decimal(0)
            equity = cash + holdings_value
            equity_curve.append({"time": bar.timestamp, "equity": equity})

            # 进度上报(节流:每 PROGRESS_REPORT_EVERY bar 或末根;失败容错见 ctx.report_progress)
            if (i + 1) % PROGRESS_REPORT_EVERY == 0 or i == total - 1:
                ctx.report_progress(i + 1, total)

        leftover = len(ctx._pending)
        if leftover:
            warnings.append(f"{leftover} order(s) placed on final bar were not executed")
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
        try:
            self._on_bar(closed, self._ctx)
        except Exception as e:  # noqa: BLE001 — on_bar 容错,记 stderr 继续(同回测容错)
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
) -> dict[str, Any]:
    period_start = klines[0]["timestamp"] if klines else ""
    period_end = klines[-1]["timestamp"] if klines else ""
    params_snapshot = dict(params)
    params_snapshot["_kwikquant"] = {**reproducibility, "warnings": warnings}
    return {
        "name": name,
        "params": params_snapshot,
        "symbol": symbol,
        "timeframe": timeframe,
        "period": {"start": str(period_start), "end": str(period_end)},
        "trades": [
            {
                "time": t.time,
                "side": t.side,
                "price": str(t.price),
                "amount": str(t.amount),
                "fee": str(t.fee),
            }
            for t in trades
        ],
        "equity_curve": [
            {"time": e["time"], "equity": str(e["equity"])} for e in equity_curve
        ],
        "metrics": {},  # Java PerformanceCalculator 重算
        "warnings": warnings,  # on_bar 异常收集(诊断用;空=策略无信号合法,非空=on_bar 有 bug)
    }
