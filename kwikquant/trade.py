"""TradeService — 下单/撤单/查持仓(Wave 8 §3.4)。

Worker 回测:``submit_backtest(task_id, order+snapshot)`` → POST /api/v1/backtests/{taskId}/orders(§4.2)。
Worker 实盘/模拟:``submit(order)`` → POST /api/v1/orders(§4.2)。
"""

from __future__ import annotations

from decimal import Decimal
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from kwikquant.client import Client


def _bd(v: Decimal | float | int | str | None) -> str | None:
    """BigDecimal 字符串序列化,None 透传。"""
    if v is None:
        return None
    return str(v) if isinstance(v, Decimal) else str(Decimal(str(v)))


class TradeService:
    def __init__(self, client: "Client") -> None:
        self._client = client

    def submit_backtest(
        self,
        task_id: int,
        *,
        symbol: str,
        side: str,
        order_type: str,
        amount: Decimal | float | str,
        price: Decimal | float | str | None,
        snapshot: dict,
        market_type: str = "SPOT",
        exchange: str,
    ) -> dict | None:
        """Worker 回测下单。返回 Fill dict 或 None(未撮合)。

        request schema 严格按 §4.2 POST /api/v1/backtests/{taskId}/orders,含 marketType+exchange
        (Java BacktestOrderRequest 契约必需字段,Round-5 BLOCKER 2 修复)。
        Header ``X-Worker-Token`` 由 :class:`Auth.service_token` 注入。
        """
        payload = {
            "symbol": symbol,
            "side": side,
            "orderType": order_type,
            "amount": _bd(amount),
            "price": _bd(price),
            "marketType": market_type,
            "exchange": exchange,
            "snapshot": _normalize_snapshot(snapshot),
        }
        resp = self._client.post(f"/api/v1/backtests/{task_id}/orders", json=payload)
        # 204 未撮合返回 {}; 200 Fill 返回 dict with orderId
        if not resp or resp.get("orderId") is None:
            return None
        return resp

    def submit(
        self,
        *,
        exchange_account_id: int,
        symbol: str,
        side: str,
        order_type: str,
        amount: Decimal | float | str,
        price: Decimal | float | str | None = None,
        time_in_force: str = "GTC",
    ) -> dict:
        """Worker 模拟/实盘下单(§3.7 Runner)。POST /api/v1/orders(§4.2)。"""
        payload = {
            "exchangeAccountId": exchange_account_id,
            "symbol": symbol,
            "side": side,
            "orderType": order_type,
            "amount": _bd(amount),
            "price": _bd(price),
            "timeInForce": time_in_force,
        }
        return self._client.post("/api/v1/orders", json=payload)

    def cancel(self, order_id: int) -> dict:
        return self._client.delete(f"/api/v1/orders/{order_id}")

    def positions(self, exchange_account_id: int) -> list[dict]:
        resp = self._client.get(
            "/api/v1/positions", params={"exchangeAccountId": exchange_account_id}
        )
        if isinstance(resp, dict):
            items = resp.get("items") if "items" in resp else resp.get("data")
            if items is None:
                # 裸列表被 _handle_response 包成 {"data": [...]}
                items = resp.get("data", [])
            return items if isinstance(items, list) else []
        return resp if isinstance(resp, list) else []


def _normalize_snapshot(snapshot: dict) -> dict:
    """OHLC → §4.2 snapshot schema。允许调用方传 Decimal/float,统一转字符串。"""
    return {
        "timestamp": snapshot["timestamp"],
        "open": _bd(snapshot.get("open")),
        "high": _bd(snapshot.get("high")),
        "low": _bd(snapshot.get("low")),
        "close": _bd(snapshot.get("close")),
        "volume": _bd(snapshot.get("volume", 0)),
    }
