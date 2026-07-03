"""Client + Auth 单元测试(Wave 8 §3.4)。"""

from __future__ import annotations

import httpx
import pytest

from kwikquant.client import Auth, Client
from kwikquant.errors import (
    KqApiError,
    KqAuthError,
    KqBacktestOrderRejected,
    KqBacktestTaskNotRunning,
    KqTimeoutError,
)


def test_auth_jwt_header():
    a = Auth.jwt("tok-1")
    assert a.as_headers() == {"Authorization": "Bearer tok-1"}


def test_auth_service_token_header():
    a = Auth.service_token("wt-abc")
    assert a.as_headers() == {"X-Worker-Token": "wt-abc"}


def test_auth_unknown_mode_raises():
    a = Auth(mode="oops", token="x")
    with pytest.raises(ValueError):
        a.as_headers()


def test_client_sends_service_token_header_and_parses_envelope(make_transport, envelope):
    seen = {}

    def _handler(req: httpx.Request):
        seen["header"] = req.headers.get("X-Worker-Token")
        seen["url"] = str(req.url)
        return httpx.Response(200, content=envelope({"orderId": 42, "symbol": "BTC/USDT"}))

    tr = make_transport([("POST", "/api/v1/backtests/1/orders", _handler)])
    with Client("http://kw", Auth.service_token("wt-xyz"), transport=tr) as c:
        resp = c.post("/api/v1/backtests/1/orders", json={"symbol": "BTC/USDT"})
    assert seen["header"] == "wt-xyz"
    assert resp["orderId"] == 42


def test_client_jwt_header_used(make_transport, envelope):
    seen = {}

    def _handler(req: httpx.Request):
        seen["auth"] = req.headers.get("Authorization")
        return httpx.Response(200, content=envelope({"total": 3}))

    tr = make_transport([("GET", "/api/v1/reports", _handler)])
    with Client("http://kw", Auth.jwt("jwtok"), transport=tr) as c:
        resp = c.get("/api/v1/reports")
    assert seen["auth"] == "Bearer jwtok"
    assert resp["total"] == 3


def test_client_401_raises_KqAuthError(make_transport, envelope):
    def _handler(req):
        return httpx.Response(401, content=envelope(code=7301, message="token expired"))

    tr = make_transport([("POST", "/api/v1/backtests/9/orders", _handler)])
    with Client("http://kw", Auth.service_token("bad"), transport=tr) as c:
        with pytest.raises(KqAuthError) as ex:
            c.post("/api/v1/backtests/9/orders", json={})
    assert ex.value.status == 401
    assert ex.value.code == 7301


def test_client_400_7302_raises_KqBacktestOrderRejected(make_transport, envelope):
    def _handler(req):
        return httpx.Response(400, content=envelope(code=7302, message="ledger insufficient"))

    tr = make_transport([("POST", "/api/v1/backtests/5/orders", _handler)])
    with Client("http://kw", Auth.service_token("t"), transport=tr) as c:
        with pytest.raises(KqBacktestOrderRejected):
            c.post("/api/v1/backtests/5/orders", json={})


def test_client_409_7303_raises_KqBacktestTaskNotRunning(make_transport, envelope):
    def _handler(req):
        return httpx.Response(409, content=envelope(code=7303, message="task not running"))

    tr = make_transport([("POST", "/api/v1/backtests/9/orders", _handler)])
    with Client("http://kw", Auth.service_token("t"), transport=tr) as c:
        with pytest.raises(KqBacktestTaskNotRunning):
            c.post("/api/v1/backtests/9/orders", json={})


def test_client_500_raises_generic_KqApiError(make_transport, envelope):
    def _handler(req):
        return httpx.Response(500, content=envelope(code=9999, message="boom"))

    tr = make_transport([("POST", "/api/v1/backtests/1/orders", _handler)])
    with Client("http://kw", Auth.service_token("t"), transport=tr) as c:
        with pytest.raises(KqApiError) as ex:
            c.post("/api/v1/backtests/1/orders", json={})
    assert ex.value.status == 500 and ex.value.code == 9999


def test_client_timeout_maps_to_KqTimeoutError():
    def _boom(req):
        raise httpx.ReadTimeout("slow")

    tr = httpx.MockTransport(_boom)
    with Client("http://kw", Auth.jwt("t"), transport=tr) as c:
        with pytest.raises(KqTimeoutError):
            c.get("/api/v1/reports")


def test_client_204_returns_empty_dict(make_transport):
    def _handler(req):
        return httpx.Response(204)

    tr = make_transport([("DELETE", "/api/v1/orders/1", _handler)])
    with Client("http://kw", Auth.jwt("t"), transport=tr) as c:
        resp = c.delete("/api/v1/orders/1")
    assert resp == {}
