"""EventLoop — 回测 / Runner 事件驱动。

函数式:策略是顶层 ``def on_bar(bar, ctx):``,event_loop 逐 bar set klines+index+snapshot
→ 调 ``on_bar(bar, ctx)`` → ``_capture`` 抓 fill → 维护 cash/equity(Decimal)→ 汇总 §8 JSON。
行情(bar.open/close…)用 float 给用户;内部金额(cash/equity/holdings)用 Decimal,
从 k 原始 str 转(不绕 float,保精度)。
"""

from __future__ import annotations

import asyncio
import logging
import sys
from dataclasses import dataclass
from decimal import Decimal
from typing import TYPE_CHECKING, Any

from kwikquant.errors import KqBacktestOrderRejected, KqBacktestTaskNotRunning
from kwikquant_worker.strategy import Bar, BacktestContext

if TYPE_CHECKING:
    from kwikquant.client import Client

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
    """逐 bar 驱动 ``on_bar(bar, ctx)``,汇总 trades + equity_curve 输出 §8 JSON。"""

    def __init__(
        self,
        *,
        initial_capital: Decimal = Decimal("100000"),
        symbol: str = "",
        timeframe: str = "",
    ) -> None:
        self.initial_capital = initial_capital
        self.symbol = symbol
        self.timeframe = timeframe

    def run(self, on_bar, ctx: BacktestContext, klines: list[dict]) -> dict[str, Any]:
        if not isinstance(ctx, BacktestContext):
            raise TypeError("BacktestEventLoop requires ctx to be BacktestContext")

        ctx.set_klines(klines)
        trades: list[_TradeRecord] = []
        equity_curve: list[dict] = []
        warnings: list[str] = []
        cash = self.initial_capital
        total = len(klines)

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
            # snapshot 给 Java 撮合:用原始 str 保 BigDecimal 精度(不绕 float)
            # last=close:MatchingKernel MARKET FAST 用 snap.last(),缺则返 None(根因:之前 0 成交)
            ctx.set_snapshot(
                {
                    "timestamp": bar.timestamp,
                    "open": str(k["open"]),
                    "high": str(k["high"]),
                    "low": str(k["low"]),
                    "close": str(k["close"]),
                    "last": str(k["close"]),
                    "volume": str(k.get("volume", 0)),
                }
            )

            fills_this_bar: list = []
            original_place = ctx.place_order

            def _capture(*args, **kwargs):
                f = original_place(*args, **kwargs)
                if f is not None:
                    fills_this_bar.append(f)
                elif len(warnings) < 10:
                    # MARKET 单该成交却返 None(撮合未成交),记 warning 诊断(截前 10 防爆)
                    warnings.append(
                        f"place_order returned None at {bar.timestamp} ({kwargs.get('order_type', '?')}/{kwargs.get('side', '?')})"
                    )
                return f

            ctx.place_order = _capture  # type: ignore[method-assign]
            try:
                on_bar(bar, ctx)
            except KqBacktestTaskNotRunning:
                # 7303 task 不 RUNNING;bubble up 让 worker_server exit 0(§3.3 异常表)
                raise
            except KqBacktestOrderRejected as e:
                # 7302 账本不足,策略常见非致命;stderr 记录,继续下一 bar(§3.3 异常表)
                log.warning("[event_loop] order rejected at %s: %s", bar.timestamp, e.message)
                if len(warnings) < 10:
                    warnings.append(f"order rejected 7302 at {bar.timestamp}: {e.message}")
            except Exception as e:  # noqa: BLE001 — 策略容错(§3.5 §6)
                msg = f"on_bar raised at {bar.timestamp}: {e!r}"
                print(f"[event_loop] {msg}", file=sys.stderr)
                warnings.append(msg)
            finally:
                ctx.place_order = original_place  # type: ignore[method-assign]

            for f in fills_this_bar:
                signed = f.qty if f.side == "BUY" else -f.qty
                cash = cash - signed * f.price - f.fee
                trades.append(
                    _TradeRecord(
                        time=f.filled_at or bar.timestamp,
                        side=f.side.lower(),
                        price=f.price,
                        amount=f.qty,
                        fee=f.fee,
                    )
                )

            pos = ctx.position(self.symbol) if self.symbol else None
            close_dec = Decimal(str(k["close"]))  # 原始 str 转,保精度
            holdings_value = (pos.qty * close_dec) if pos and pos.qty != 0 else Decimal(0)
            equity = cash + holdings_value
            equity_curve.append({"time": bar.timestamp, "equity": equity})

            # 进度上报(节流:每 PROGRESS_REPORT_EVERY bar 或末根;失败容错见 ctx.report_progress)
            if (i + 1) % PROGRESS_REPORT_EVERY == 0 or i == total - 1:
                ctx.report_progress(i + 1, total)

        if len(warnings) > 10:
            warnings = warnings[:10] + [f"...{len(warnings) - 10} more warnings"]
        return _to_section8(
            name="backtest",
            params={},
            symbol=self.symbol,
            timeframe=self.timeframe,
            klines=klines,
            trades=trades,
            equity_curve=equity_curve,
            warnings=warnings,
        )


class RunnerEventLoop:
    """模拟盘/实盘长驻循环 — StreamClient 订阅 /topic/kline → bar 关闭检测 → on_bar(bar, ctx)。

    与回测 BacktestEventLoop 对偶:回测逐 bar 喂历史(on_bar 每根),实盘 WS 推 kline 实时
    更新(尾根替换),runner 做 bar 关闭检测(openTime 变化=前一根关闭→用前一根调 on_bar)。
    函数式 on_bar(bar, ctx) 与回测统一(用户一份策略通吃回测+live)。止损止盈靠交易所条件单
    (OKX stop-limit/OCO,on_bar 内 ctx.place_order 下条件单),不依赖 on_tick。
    """

    def __init__(self) -> None:
        self._current_bar: Bar | None = None
        self._on_bar = None
        self._ctx = None

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
        bar = self._to_bar(payload)
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
        except Exception as e:  # noqa: BLE001 — on_bar 容错,记 stderr 继续(同回测 §3.5 §6)
            print(f"[runner] on_bar raised at {closed.timestamp}: {e!r}", file=sys.stderr)

    @staticmethod
    def _to_bar(payload: dict) -> Bar:
        """Kline WS payload({openTime, open, high, low, close, volume})→ Bar(行情 float)。"""
        return Bar(
            timestamp=str(payload.get("openTime", "")),
            open=float(str(payload.get("open", 0))),
            high=float(str(payload.get("high", 0))),
            low=float(str(payload.get("low", 0))),
            close=float(str(payload.get("close", 0))),
            volume=float(str(payload.get("volume", 0))),
        )


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
) -> dict[str, Any]:
    period_start = klines[0]["timestamp"] if klines else ""
    period_end = klines[-1]["timestamp"] if klines else ""
    return {
        "name": name,
        "params": params,
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
        "metrics": {},  # Java PerformanceCalculator 重算(§4.4)
        "warnings": warnings,  # on_bar 异常收集(诊断用;空=策略无信号合法,非空=on_bar 有 bug)
    }
