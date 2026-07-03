"""``kwikquant.client`` — REST transport + 可插拔 Auth(Wave 8 §3.4)。

Auth 两模式:
- ``Auth.jwt(token)``:外部用户,``Authorization: Bearer <jwt>``
- ``Auth.service_token(token)``:Worker,``X-Worker-Token: <uuid>``(§3.2 合约)
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

import httpx

from kwikquant.errors import (
    KqApiError,
    KqAuthError,
    KqBacktestOrderRejected,
    KqBacktestTaskNotRunning,
    KqTimeoutError,
)

DEFAULT_TIMEOUT_SEC = 30.0


@dataclass(frozen=True)
class Auth:
    """可插拔认证。header 由 ``as_headers()`` 返回。"""

    mode: str  # "jwt" | "service_token"
    token: str

    @staticmethod
    def jwt(token: str) -> "Auth":
        return Auth(mode="jwt", token=token)

    @staticmethod
    def service_token(token: str) -> "Auth":
        return Auth(mode="service_token", token=token)

    def as_headers(self) -> dict[str, str]:
        if self.mode == "jwt":
            return {"Authorization": f"Bearer {self.token}"}
        if self.mode == "service_token":
            return {"X-Worker-Token": self.token}
        raise ValueError(f"unknown auth mode: {self.mode}")


class Client:
    """轻量 REST client。thin — 每方法 = 一次 HTTP 调用。

    Args:
        base_url: Java API 根 URL(如 ``http://kwikquant:8080``)。
        auth: :class:`Auth` 实例。
        timeout: HTTP 超时秒数,默认 30。
        transport: 可注入 httpx transport,用于测试(mock)。
    """

    def __init__(
        self,
        base_url: str,
        auth: Auth,
        *,
        timeout: float = DEFAULT_TIMEOUT_SEC,
        transport: httpx.BaseTransport | None = None,
    ) -> None:
        self.base_url = base_url.rstrip("/")
        self.auth = auth
        self._http = httpx.Client(
            base_url=self.base_url,
            headers=auth.as_headers(),
            timeout=timeout,
            transport=transport,
        )

    def close(self) -> None:
        self._http.close()

    def __enter__(self) -> "Client":
        return self

    def __exit__(self, *_exc: Any) -> None:
        self.close()

    # --- HTTP primitives (内部使用) ---
    def _request(
        self,
        method: str,
        path: str,
        *,
        json: dict | None = None,
        params: dict | None = None,
    ) -> dict:
        try:
            resp = self._http.request(method, path, json=json, params=params)
        except httpx.TimeoutException as e:
            raise KqTimeoutError(f"HTTP {method} {path} timed out: {e}") from e
        return _handle_response(resp)

    def get(self, path: str, *, params: dict | None = None) -> dict:
        return self._request("GET", path, params=params)

    def post(self, path: str, *, json: dict | None = None) -> dict:
        return self._request("POST", path, json=json)

    def delete(self, path: str) -> dict:
        return self._request("DELETE", path)

    # --- service accessors (lazy) ---
    @property
    def data(self) -> "DataService":
        from kwikquant.data import DataService

        return DataService(self)

    @property
    def trade(self) -> "TradeService":
        from kwikquant.trade import TradeService

        return TradeService(self)

    @property
    def report(self) -> "ReportService":
        from kwikquant.report import ReportService

        return ReportService(self)

    @property
    def account(self) -> "AccountService":
        from kwikquant.account import AccountService

        return AccountService(self)


def _handle_response(resp: httpx.Response) -> dict:
    """解析响应 → dict;非 2xx 抛专属异常。

    KwikQuant API 返回统一 envelope: ``{code, message, data}``。
    2xx + code=0 → 返回 ``data``;其他 → 抛 :class:`KqApiError` 或子类。
    """
    body_text = resp.text
    try:
        payload = resp.json() if body_text else {}
    except ValueError:
        payload = {}

    if resp.status_code == 204 or (resp.status_code == 200 and not payload):
        return {}

    code = payload.get("code") if isinstance(payload, dict) else None
    message = payload.get("message", "") if isinstance(payload, dict) else ""

    if 200 <= resp.status_code < 300:
        # envelope 或裸对象;envelope 用 data,裸对象直接返回。裸 list 包装 data 字段。
        if isinstance(payload, dict) and "code" in payload and "data" in payload:
            data = payload.get("data")
            return data if isinstance(data, dict) else {"data": data}
        return payload if isinstance(payload, dict) else {"data": payload}

    # 错误分流(基于 status + errorCode)
    if resp.status_code == 401:
        raise KqAuthError(resp.status_code, code, message, body_text)
    if resp.status_code == 400 and code == 7302:
        raise KqBacktestOrderRejected(resp.status_code, code, message, body_text)
    if resp.status_code == 409 and code == 7303:
        raise KqBacktestTaskNotRunning(resp.status_code, code, message, body_text)
    raise KqApiError(resp.status_code, code, message, body_text)
