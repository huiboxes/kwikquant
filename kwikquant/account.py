"""AccountService — 用户/交易账户查询(Wave 8 §3.4)。"""

from __future__ import annotations

from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from kwikquant.client import Client


class AccountService:
    def __init__(self, client: "Client") -> None:
        self._client = client

    def exchange_accounts(self) -> list[dict]:
        resp = self._client.get("/api/v1/exchange-accounts")
        items = resp.get("items") if isinstance(resp, dict) else resp
        return items if isinstance(items, list) else []

    def balance(self, exchange_account_id: int) -> dict:
        return self._client.get(f"/api/v1/exchange-accounts/{exchange_account_id}/balance")
