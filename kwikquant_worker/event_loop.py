"""EventLoop — 回测 / Runner 事件驱动。

函数式:策略是顶层 ``def on_bar(bar, ctx):``,event_loop 逐 bar set klines+index+snapshot
→ 调 ``on_bar(bar, ctx)`` → ``_capture`` 抓 fill → 维护 cash/equity(Decimal)→ 汇总 §8 JSON。
行情(bar.open/close…)用 float 给用户;内部金额(cash/equity/holdings)用 Decimal,
从 k 原始 str 转(不绕 float,保精度)。
"""

from __future__ import annotations

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
    """模拟盘/实盘长驻循环 — Runner 订阅行情 WS,收 tick/bar 调策略。

    生产实现在 :meth:`run` 内 asyncio 起 StreamClient.run;§3.7 完成。函数式 on_bar(bar, ctx)
    与回测统一(用户一份策略通吃回测+live)。
    """

    def run(self, on_bar, ws_client: Any, api_client: "Client") -> None:
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
