"""AccountService 单元测试(端点路径 + worker 余额通道)。

历史 bug:SDK 用旧路径 /api/v1/exchange-accounts(实际控制器是 /api/v1/accounts),
且 exchange_accounts() 从 {"data": [...]} 里取 "items" 恒返 []——此处一并钉死修正后行为。
"""

from __future__ import annotations

import json

import httpx

from kwikquant.client import Auth, Client


def test_exchange_accounts_uses_accounts_path_and_unwraps_data(make_transport, envelope):
    seen = {}

    def _handler(req):
        seen["path"] = str(req.url.path)
        return httpx.Response(200, content=envelope([{"id": 1, "label": "OKX 模拟"}]))

    tr = make_transport([("GET", "/api/v1/accounts", _handler)])
    with Client("http://kw", Auth.jwt("t"), transport=tr) as c:
        accounts = c.account.exchange_accounts()
    assert seen["path"] == "/api/v1/accounts"
    assert accounts == [{"id": 1, "label": "OKX 模拟"}]


def test_balance_uses_accounts_id_path(make_transport, envelope):
    seen = {}

    def _handler(req):
        seen["path"] = str(req.url.path)
        return httpx.Response(200, content=envelope({"currencies": {"USDT": {"free": "1", "used": "0", "total": "1"}}}))

    tr = make_transport([("GET", "/api/v1/accounts/7/balance", _handler)])
    with Client("http://kw", Auth.jwt("t"), transport=tr) as c:
        snap = c.account.balance(7)
    assert seen["path"] == "/api/v1/accounts/7/balance"
    assert snap["currencies"]["USDT"]["total"] == "1"


def test_worker_balance_sends_market_type(make_transport, envelope):
    """worker 余额通道:GET /api/v1/accounts/worker/balance?marketType=PERP(X-Worker-Token)。"""
    seen = {}

    def _handler(req):
        seen["path"] = str(req.url.path)
        seen["marketType"] = req.url.params.get("marketType")
        seen["token"] = req.headers.get("X-Worker-Token")
        return httpx.Response(
            200,
            content=envelope({"currencies": {"USDT": {"free": "900.5", "used": "0", "total": "900.5"}}}),
        )

    tr = make_transport([("GET", "/api/v1/accounts/worker/balance", _handler)])
    with Client("http://kw", Auth.service_token("wt-9"), transport=tr) as c:
        snap = c.account.worker_balance(market_type="PERP")
    assert seen["path"] == "/api/v1/accounts/worker/balance"
    assert seen["marketType"] == "PERP"
    assert seen["token"] == "wt-9"
    assert json.loads(json.dumps(snap))["currencies"]["USDT"]["free"] == "900.5"


def test_worker_balance_defaults_spot(make_transport, envelope):
    seen = {}

    def _handler(req):
        seen["marketType"] = req.url.params.get("marketType")
        return httpx.Response(200, content=envelope({"currencies": {}}))

    tr = make_transport([("GET", "/api/v1/accounts/worker/balance", _handler)])
    with Client("http://kw", Auth.service_token("wt-9"), transport=tr) as c:
        c.account.worker_balance()
    assert seen["marketType"] == "SPOT"
