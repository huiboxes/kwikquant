"""AccountService — 用户/交易账户查询。

端点前缀是 ``/api/v1/accounts``(ExchangeAccountController @RequestMapping);
worker 通道另有 ``/api/v1/accounts/worker/balance``(RUNNER token,账户由绑定推导)。
"""

from __future__ import annotations

from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from kwikquant.client import Client


class AccountService:
    def __init__(self, client: "Client") -> None:
        self._client = client

    def exchange_accounts(self) -> list[dict]:
        resp = self._client.get("/api/v1/accounts")
        if isinstance(resp, list):
            return resp
        if isinstance(resp, dict):
            # ApiResponse<List> 解包后是裸 list,被 _handle_response 包成 {"data": [...]}
            for key in ("items", "data"):
                items = resp.get(key)
                if isinstance(items, list):
                    return items
        return []

    def balance(self, exchange_account_id: int) -> dict:
        """查账户余额(BalanceSnapshot)。currencies 各币的 free/used/total 是
        **decimal string**(金额红线),``Decimal(str(...))`` 直读,勿经 float 中转。"""
        return self._client.get(f"/api/v1/accounts/{exchange_account_id}/balance")

    def worker_balance(self, market_type: str = "SPOT") -> dict:
        """Worker 通道查绑定账户余额(X-Worker-Token RUNNER,账户由 token 绑定推导)。

        ``GET /api/v1/accounts/worker/balance?marketType=SPOT|PERP``。runner 策略
        ``ctx.equity()``/``ctx.available_cash()`` 的数据源;返 BalanceSnapshot
        ``{currencies: {币种: {free, used, total}}}``(金额为 decimal string)。
        """
        return self._client.get("/api/v1/accounts/worker/balance", params={"marketType": market_type})
