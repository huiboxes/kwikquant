"""DataService — 历史 K 线 / ticker。"""

from __future__ import annotations

from typing import TYPE_CHECKING, Any

if TYPE_CHECKING:
    from kwikquant.client import Client


class DataService:
    def __init__(self, client: "Client") -> None:
        self._client = client

    def ohlcv(
        self,
        exchange: str,
        market_type: str,
        symbol: str,
        interval: str,
        *,
        limit: int = 100,
        before: str | None = None,
    ) -> list[dict[str, Any]]:
        """GET /api/v1/market/klines → list of Kline dict ``{openTime, open, high, low, close, volume}``
        (+ exchange/marketType/symbol/interval,多余字段调用方忽略)。

        按 ``limit``(1-1000,默认 100)返最近 N 根;``before``(ISO-8601,如 2026-07-17T10:00:00Z)
        返 open_time < before 的最近 N 根(往前翻页)。返空 list 表示该交易对该周期无历史数据。

        Runner 重启后用此预填 ``RunnerContext._bars`` 历史(消除"重启失忆")。SDK 返 list[dict]
        保持无外部依赖;用户可自行 ``pd.DataFrame(resp)``。
        """
        params: dict[str, Any] = {
            "exchange": exchange,
            "marketType": market_type,
            "symbol": symbol,
            "interval": interval,
            "limit": limit,
        }
        if before is not None:
            params["before"] = before
        resp = self._client.get("/api/v1/market/klines", params=params)
        items = resp.get("data") if isinstance(resp, dict) else resp
        return items if isinstance(items, list) else []

    def klines_recent(
        self,
        exchange: str,
        market_type: str,
        symbol: str,
        interval: str,
        limit: int,
    ) -> list[dict[str, Any]]:
        """GET /api/v1/market/klines(limit 语义,最近 N 根)→ ``[{openTime, open, ...}]``。

        Runner 启动 warmup 回填用(worker token 通道,WorkerTokenFilter 放行)。
        注意返回可能含未收完的当前活 bar,调用方按需丢尾根。
        """
        resp = self._client.get(
            "/api/v1/market/klines",
            params={
                "exchange": exchange,
                "marketType": market_type,
                "symbol": symbol,
                "interval": interval,
                "limit": limit,
            },
            # 首次未命中缓存时 Java 侧 API-first 拉 CCXT,可能几十秒
            timeout=120.0,
        )
        raw = resp if isinstance(resp, list) else (resp.get("data") if isinstance(resp, dict) else None)
        return raw if isinstance(raw, list) else []

    def ticker(self, exchange: str, market_type: str, symbol: str) -> dict:
        """GET /api/v1/market/ticker/{exchange}/{marketType}/{symbol} → ticker dict。

        symbol 里 ``/`` 在 URL 用 ``-`` 替代（BTC/USDT → BTC-USDT），controller
        内部还原。后端返 envelope，client 已解包 data，返回 ``{ticker, stale}``。
        """
        symbol_url = symbol.replace("/", "-")
        path = f"/api/v1/market/ticker/{exchange}/{market_type}/{symbol_url}"
        return self._client.get(path)
