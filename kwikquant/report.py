"""ReportService — §8 JSON 上传(Wave 8 §3.4 + §4.4)。

外部用户自选框架(vectorbt/backtrader)本地跑完 → ``adapters.to_section8`` → ``client.report.upload(§8)``。
"""

from __future__ import annotations

from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from kwikquant.client import Client


class ReportService:
    def __init__(self, client: "Client") -> None:
        self._client = client

    def upload(self, section8: dict) -> dict:
        """POST /api/v1/reports (Java `ReportService.importResult` source=IMPORT)。"""
        return self._client.post("/api/v1/reports", json=section8)

    def list(self, *, symbol: str | None = None, page: int = 1, page_size: int = 20) -> dict:
        params: dict = {"page": page, "pageSize": page_size}
        if symbol is not None:
            params["symbol"] = symbol
        return self._client.get("/api/v1/reports", params=params)

    def get(self, report_id: int) -> dict:
        return self._client.get(f"/api/v1/reports/{report_id}")
