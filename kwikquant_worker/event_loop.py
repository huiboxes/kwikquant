"""EventLoop — 回测 / Runner 事件驱动(Wave 8 §3.5)。"""

from __future__ import annotations

import logging
import sys
from dataclasses import dataclass
from decimal import Decimal
from typing import TYPE_CHECKING, Any

from kwikquant.errors import KqBacktestOrderRejected, KqBacktestTaskNotRunning
from kwikquant_worker.strategy import Bar, BacktestContext, Strategy

if TYPE_CHECKING:
    from kwikquant.client import Client

log = logging.getLogger(__name__)


@dataclass
class _TradeRecord:
    time: str
    side: str
    price: Decimal
    amount: Decimal
    fee: Decimal


class BacktestEventLoop:
    """逐 bar 驱动 Strategy.on_bar,汇总 trades + equity_curve 输出 §8 JSON。

    task_id / meta 由 caller(worker_server)提供,event_loop 只负责事件顺序 + 汇总。
    """

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

    def run(self, strategy: Strategy, klines: list[dict], api_client: "Client") -> dict[str, Any]:
        ctx = strategy.ctx
        if not isinstance(ctx, BacktestContext):
            raise TypeError("BacktestEventLoop requires strategy.ctx to be BacktestContext")

        trades: list[_TradeRecord] = []
        equity_curve: list[dict] = []
        cash = self.initial_capital

        for k in klines:
            bar = Bar(
                timestamp=str(k["timestamp"]),
                open=Decimal(str(k["open"])),
                high=Decimal(str(k["high"])),
                low=Decimal(str(k["low"])),
                close=Decimal(str(k["close"])),
                volume=Decimal(str(k.get("volume", 0))),
            )
            ctx.set_snapshot(
                {
                    "timestamp": bar.timestamp,
                    "open": bar.open,
                    "high": bar.high,
                    "low": bar.low,
                    "close": bar.close,
                    "volume": bar.volume,
                }
            )
            fills_this_bar: list = []
            original_submit = ctx.submit_order

            def _capture(*args, **kwargs):
                f = original_submit(*args, **kwargs)
                if f is not None:
                    fills_this_bar.append(f)
                return f

            ctx.submit_order = _capture  # type: ignore[method-assign]
            try:
                strategy.on_bar(bar)
            except KqBacktestTaskNotRunning:
                # 7303 task 不 RUNNING;bubble up 让 worker_server exit 0(§3.3 异常表)
                raise
            except KqBacktestOrderRejected as e:
                # 7302 账本不足,策略常见非致命;stderr 记录,继续下一 bar(§3.3 异常表)
                log.warning("[event_loop] order rejected at %s: %s", bar.timestamp, e.message)
            except Exception as e:  # noqa: BLE001 — 策略容错(§3.5 §6)
                print(f"[event_loop] strategy on_bar raised at {bar.timestamp}: {e!r}", file=sys.stderr)
            finally:
                ctx.submit_order = original_submit  # type: ignore[method-assign]

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
                strategy.on_fill(f)

            pos = ctx.position(self.symbol) if self.symbol else None
            holdings_value = (pos.qty * bar.close) if pos and pos.qty != 0 else Decimal(0)
            equity = cash + holdings_value
            equity_curve.append({"time": bar.timestamp, "equity": equity})

        return _to_section8(
            name=getattr(strategy, "name", "backtest"),
            params=strategy.parameters,
            symbol=self.symbol,
            timeframe=self.timeframe,
            klines=klines,
            trades=trades,
            equity_curve=equity_curve,
        )


class RunnerEventLoop:
    """模拟盘/实盘长驻循环 — Runner 订阅行情 WS,收 tick/bar 调 strategy。

    生产实现在 :meth:`run` 内 asyncio 起 StreamClient.run;Wave 8 code-impl 只搭骨架,
    真实容器整合在 §3.7。
    """

    def run(self, strategy: Strategy, ws_client: Any, api_client: "Client") -> None:
        raise NotImplementedError(
            "RunnerEventLoop.run 实盘/模拟长驻依赖 StreamClient async 实现,§3.7 完成"
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
    }
