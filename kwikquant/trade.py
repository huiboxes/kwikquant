"""TradeService — 下单/撤单/查持仓。

Worker 回测:``get_klines``(拉数据)+ ``report_progress``(进度上报);撮合已本地化
(Wave 2.2,``kwikquant_worker/backtest/matching.py``),不再有回测下单 HTTP 通道。
Worker 实盘/模拟:``submit(order)`` → POST /api/v1/orders。
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

    def report_progress(self, task_id: int, processed: int, total: int) -> None:
        """逐 bar 进度上报(Worker 通道,X-Worker-Token 注入)。

        ``POST /api/v1/backtests/{taskId}/progress`` body ``{processedBars, totalBars}``。
        Java 收到后写 backtest_tasks + 发 WS RUNNING 增量(前端进度条)。返 204,
        无返回值;失败抛 KqApiError 由 caller(BacktestContext.report_progress)容错吞掉。
        """
        self._client.post(
            f"/api/v1/backtests/{task_id}/progress",
            json={"processedBars": processed, "totalBars": total},
        )

    def get_klines(
        self,
        task_id: int,
        *,
        exchange: str,
        market_type: str,
        symbol: str,
        interval: str,
        start: str,
        end: str,
    ) -> list[dict]:
        """Worker 回测拉历史 K 线(Worker 通道)。GET /api/v1/backtests/{taskId}/klines,
        走 Java fetchKlineRangeDbFirst(DB-first + API 补漏,数据快照落 klines 表真复现)。

        Java Kline record 字段(openTime/open/high/low/close/volume)映射成 worker event_loop
        期望格式(timestamp/open/...);返 [] 表示区间无数据(上层 exit 2 → Java markFailed 7304)。
        """
        resp = self._client.get(
            f"/api/v1/backtests/{task_id}/klines",
            params={
                "exchange": exchange,
                "marketType": market_type,
                "symbol": symbol,
                "interval": interval,
                "start": start,
                "end": end,
            },
            # 回测拉一年区间历史 K 线:Java fetchKlineRangeApiFirst 分页调 CCXT fetchOHLCV
            # (1h×365≈8760 根,分页多次往返),首次未命中 Caffeine 缓存需几十秒,30s 默认超时不够
            timeout=300.0,
        )
        raw = resp.get("data") if isinstance(resp, dict) else resp
        if not isinstance(raw, list):
            return []
        return [
            {
                "timestamp": k.get("openTime"),
                "open": k.get("open"),
                "high": k.get("high"),
                "low": k.get("low"),
                "close": k.get("close"),
                "volume": k.get("volume"),
            }
            for k in raw
        ]

    def submit(
        self,
        *,
        symbol: str,
        side: str,
        order_type: str,
        amount: Decimal | float | str,
        price: Decimal | float | str | None = None,
        time_in_force: str = "GTC",
        market_type: str = "SPOT",
        exchange_account_id: int | None = None,
        leverage: int | None = None,
        margin_mode: str | None = None,
        position_effect: str | None = None,
    ) -> dict:
        """Worker 模拟/实盘下单(Runner)。POST /api/v1/orders。

        worker 模式(RUNNER token):``exchange_account_id`` 不传(None),OrderController 据
        ``X-Worker-Token`` 推导 account;``marketType`` 必填(OrderSubmitRequest
        @NotBlank)。PERP 字段(leverage/marginMode/positionEffect)按需透传。
        """
        payload: dict = {
            "symbol": symbol,
            "side": side,
            "orderType": order_type,
            "amount": _bd(amount),
            "marketType": market_type,
            "timeInForce": time_in_force,
        }
        if price is not None:
            payload["price"] = _bd(price)
        if exchange_account_id is not None:
            payload["exchangeAccountId"] = exchange_account_id
        if leverage is not None:
            payload["leverage"] = leverage
        if margin_mode is not None:
            payload["marginMode"] = margin_mode
        if position_effect is not None:
            payload["positionEffect"] = position_effect
        return self._client.post("/api/v1/orders", json=payload)

    def cancel(self, order_id: int) -> dict:
        return self._client.delete(f"/api/v1/orders/{order_id}")

    def positions(
        self,
        exchange_account_id: int | None = None,
        *,
        symbol: str | None = None,
    ) -> list[dict]:
        """查持仓。worker 模式 ``exchange_account_id=None``(后端据 X-Worker-Token 推导,
        PositionController.list worker token 分流)。返 list[dict](PositionDto)。"""
        params: dict = {}
        if exchange_account_id is not None:
            params["exchangeAccountId"] = exchange_account_id
        if symbol is not None:
            params["symbol"] = symbol
        resp = self._client.get("/api/v1/positions", params=params)
        if isinstance(resp, dict):
            items = resp.get("items") if "items" in resp else resp.get("data")
            if items is None:
                # 裸列表被 _handle_response 包成 {"data": [...]}
                items = resp.get("data", [])
            return items if isinstance(items, list) else []
        return resp if isinstance(resp, list) else []
