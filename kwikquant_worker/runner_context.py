"""RunnerContext — 实盘/模拟盘 Runner 的策略 ctx。

与 BacktestContext 对偶:place_order 走 ``trade.submit``(POST /api/v1/orders,worker token 推导
account,不传 exchange_account_id);position 走 REST ``/api/v1/positions``(worker token 推导);
history 切片内存 ``_bars``(由 RunnerEventLoop 收 bar 关闭后 set_bar 填,初始空 warmup)。

place_order 返回 Fill 兼容回测返回结构(从 OrderSubmitResult 提取 orderId/filledQty/filledAvgPrice),
但语义不同:实盘订单可能 NEW(限价未成交,filledQty=0)或 FILLED(市价即时成交),策略按
``if f:`` 判提交成功、``f.qty > 0`` 判成交;详细成交靠 /topic/fills 推送或 position 查。
"""

from __future__ import annotations

import sys
from decimal import Decimal
from typing import TYPE_CHECKING

from kwikquant_worker.strategy import Bar, Fill, Position

if TYPE_CHECKING:
    from kwikquant.client import Client
    from kwikquant_worker.health_signals import HealthSignals


class RunnerContext:
    """Runner ctx:策略 on_bar 内读 history + 下单 + 查持仓(实盘/模拟盘)。"""

    def __init__(
        self,
        client: "Client",
        strategy_id: int,
        *,
        exchange: str,
        market_type: str,
        symbol: str,
        health_signals: "HealthSignals | None" = None,
    ) -> None:
        self._client = client
        self._strategy_id = strategy_id
        self._exchange = exchange
        self._market_type = market_type
        self._symbol = symbol
        self._bars: list[Bar] = []
        self._index: int = -1
        self._signals = health_signals

    def set_bar(self, bar: Bar) -> None:
        """RunnerEventLoop bar 关闭后调:append + 推进 index(history 切片含当前 bar)。"""
        self._bars.append(bar)
        self._index = len(self._bars) - 1

    def prefill_bars(self, bars: list[Bar]) -> None:
        """WS 连接前预填历史 bar(消除 runner 重启"失忆"):一次性灌入已关闭的历史 bar。

        与 ``set_bar``(逐根 append)不同:预填直接替换 ``_bars`` + ``_index``,**不动**
        ``_current_bar``(由 WS ``_on_kline`` 首根缓存)。调用方(``worker_server._prefill_history``)
        须排除末根可能未关闭的 bar——否则 WS 推同 openTime 首根缓存→关闭后 ``set_bar`` 再 append 会重复。
        空 list → ``_index=-1``(history 返 [],等同无预填,WS 路径照常)。
        """
        self._bars = list(bars)
        self._index = len(self._bars) - 1

    @property
    def symbol(self) -> str:
        return self._symbol

    def history(self, field: str, n: int) -> list[float]:
        """最近 n 根(含当前)K 线的 field 值。不足 n(开头 warmup)返已有;index 未 set 返 []。"""
        if self._index < 0 or not self._bars:
            return []
        start = max(0, self._index - n + 1)
        return [getattr(b, field) for b in self._bars[start : self._index + 1]]

    def place_order(
        self,
        *,
        side: str,
        order_type: str,
        amount,
        price=None,
        leverage: int | None = None,
        margin_mode: str | None = None,
        position_effect: str | None = None,
    ) -> Fill | None:
        """实盘下单。调 trade.submit(POST /api/v1/orders,worker token 推导 account,不传 accountId)。

        失败(网络/被拒)返 None,不中断 runner(记 stderr)。返 Fill 从 OrderSubmitResult 提取
        (orderId/filledQty/filledAvgPrice);限价 NEW 订单 filledQty=0(策略按 qty 判成交)。
        """
        try:
            resp = self._client.trade.submit(
                symbol=self._symbol,
                side=side,
                order_type=order_type,
                amount=amount,
                price=price,
                market_type=self._market_type,
                leverage=leverage,
                margin_mode=margin_mode,
                position_effect=position_effect,
            )
        except Exception as e:  # noqa: BLE001 — 下单失败不中断 runner
            self._record_order_outcome(False)
            print(f"[runner] place_order failed: {e!r}", file=sys.stderr)
            return None
        if not isinstance(resp, dict) or resp.get("orderId") is None:
            self._record_order_outcome(False)
            return None
        raw_side = resp.get("side", side)
        self._record_order_outcome(True)
        return Fill(
            order_id=int(resp.get("orderId", 0)),
            symbol=resp.get("symbol", self._symbol),
            side=raw_side.upper() if isinstance(raw_side, str) else side,
            price=Decimal(str(resp.get("filledAvgPrice") or resp.get("price") or 0)),
            qty=Decimal(str(resp.get("filledQty") or 0)),
            fee=Decimal(str(resp.get("fee") or 0)),
            fee_currency=resp.get("feeCurrency", ""),
            filled_at=resp.get("updatedAt") or resp.get("createdAt") or "",
        )

    def _record_order_outcome(self, ok: bool) -> None:
        """下单结果上报 HealthSignals(成功重置连续失败为 0,失败累加)。None 时 no-op。"""
        if self._signals is not None:
            self._signals.record_order_outcome(ok=ok)

    def position(self, symbol: str) -> Position:
        """查持仓(REST /positions,worker token 推导 account)。失败/无持仓返空 Position(qty=0)。"""
        try:
            items = self._client.trade.positions(symbol=symbol)
        except Exception as e:  # noqa: BLE001
            print(f"[runner] position query failed: {e!r}", file=sys.stderr)
            return Position(symbol=symbol, qty=Decimal(0), avg_price=Decimal(0))
        for it in items:
            if isinstance(it, dict) and it.get("symbol") == symbol:
                return Position(
                    symbol=symbol,
                    qty=Decimal(str(it.get("qty", 0))),
                    avg_price=Decimal(str(it.get("avgPrice") or it.get("avg_price") or 0)),
                )
        return Position(symbol=symbol, qty=Decimal(0), avg_price=Decimal(0))

    def log(self, msg: str) -> None:
        print(f"[strategy] {msg}", file=sys.stderr)

    def report_progress(self, processed: int, total: int) -> None:
        """runner 无 task 进度概念,无 op(进度上报仅回测 task 有)。"""
        return None
