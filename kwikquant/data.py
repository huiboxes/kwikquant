"""DataService — 历史 K 线 / ticker(Wave 8 §3.4)。"""

from __future__ import annotations

from datetime import datetime
from typing import TYPE_CHECKING, Any

if TYPE_CHECKING:
    from kwikquant.client import Client


class DataService:
    def __init__(self, client: "Client") -> None:
        self._client = client

    def ohlcv(
        self,
        exchange: str,
        symbol: str,
        interval: str,
        start: datetime,
        end: datetime,
    ) -> list[dict[str, Any]]:
        """GET /api/v1/market/klines → list of ``{time, open, high, low, close, volume}``。

        SDK 返回 list[dict] 保持无外部依赖;用户可自行 ``pd.DataFrame(resp)``。
        """
        resp = self._client.get(
            "/api/v1/market/klines",
            params={
                "exchange": exchange,
                "symbol": symbol,
                "interval": interval,
                "start": start.isoformat(),
                "end": end.isoformat(),
            },
        )
        items = resp.get("items") if isinstance(resp, dict) else resp
        return items if isinstance(items, list) else []

    def ticker(self, exchange: str, symbol: str) -> dict:
        return self._client.get(
            "/api/v1/market/ticker", params={"exchange": exchange, "symbol": symbol}
        )
