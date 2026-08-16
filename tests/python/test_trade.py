"""TradeService — request/response schema 合约测试。

撮合本地化(Wave 2.2)后回测通道仅剩 get_klines(数据)+ report_progress(进度);
回测下单端点已删除,相应测试随之移除(差分对拍转 tests/fixtures/matching)。
"""

from __future__ import annotations

import json

import httpx

from kwikquant.client import Auth, Client


def test_report_progress_posts_bars(make_transport, envelope):
    """进度上报 body {processedBars, totalBars},Worker 通道 X-Worker-Token 注入。"""
    captured = {}

    def _handler(req: httpx.Request):
        captured["path"] = str(req.url.path)
        captured["header"] = req.headers.get("X-Worker-Token")
        captured["body"] = json.loads(req.content)
        return httpx.Response(204)

    tr = make_transport([("POST", "/api/v1/backtests/9/progress", _handler)])
    with Client("http://kw", Auth.service_token("wt-1"), transport=tr) as c:
        c.trade.report_progress(9, 200, 8760)
    assert captured["path"] == "/api/v1/backtests/9/progress"
    assert captured["header"] == "wt-1"
    assert captured["body"] == {"processedBars": 200, "totalBars": 8760}


def test_submit_live_order_uses_orders_endpoint(make_transport, envelope):
    seen = {}

    def _handler(req):
        seen["path"] = str(req.url.path)
        seen["body"] = json.loads(req.content)
        return httpx.Response(200, content=envelope({"orderId": 100}))

    tr = make_transport([("POST", "/api/v1/orders", _handler)])
    with Client("http://kw", Auth.service_token("t"), transport=tr) as c:
        r = c.trade.submit(exchange_account_id=5, symbol="ETH/USDT", side="SELL",
                           order_type="LIMIT", amount="0.5", price="3000")
    assert seen["path"] == "/api/v1/orders"
    assert seen["body"]["exchangeAccountId"] == 5
    assert seen["body"]["timeInForce"] == "GTC"
    assert r["orderId"] == 100


def test_cancel_calls_delete(make_transport, envelope):
    def _handler(req):
        assert req.method == "DELETE"
        return httpx.Response(204)

    tr = make_transport([("DELETE", "/api/v1/orders/42", _handler)])
    with Client("http://kw", Auth.jwt("t"), transport=tr) as c:
        r = c.trade.cancel(42)
    assert r == {}


def test_positions_returns_list_even_for_bare_array(make_transport):
    body = json.dumps([{"symbol": "BTC/USDT", "qty": "0.1"}]).encode()

    def _handler(req):
        return httpx.Response(200, content=body)

    tr = make_transport([("GET", "/api/v1/positions", _handler)])
    with Client("http://kw", Auth.jwt("t"), transport=tr) as c:
        pos = c.trade.positions(1)
    assert pos == [{"symbol": "BTC/USDT", "qty": "0.1"}]


def test_submit_perp_serializes_leverage_margin_mode_position_effect(make_transport, envelope):
    """PERP 下单 payload 序列化 leverage/marginMode/positionEffect(camelCase),合约字段。

    worker 模式不传 exchangeAccountId(后端据 X-Worker-Token 推导);PERP 四字段必透传,
    否则后端 OrderSubmitCommand.perp 拿不到 leverage → 风控 MaxInitialMarginEvaluator fail-closed。
    """
    captured = {}

    def _handler(req: httpx.Request):
        captured["body"] = json.loads(req.content)
        return httpx.Response(200, content=envelope({"orderId": 200}))

    tr = make_transport([("POST", "/api/v1/orders", _handler)])
    with Client("http://kw", Auth.service_token("t"), transport=tr) as c:
        r = c.trade.submit(
            symbol="BTC/USDT",
            side="BUY",
            order_type="MARKET",
            amount="0.1",
            market_type="PERP",
            leverage=10,
            margin_mode="ISOLATED",
            position_effect="OPEN_LONG",
        )
    body = captured["body"]
    assert body["marketType"] == "PERP"
    assert body["leverage"] == 10
    assert body["marginMode"] == "ISOLATED"
    assert body["positionEffect"] == "OPEN_LONG"
    # worker 模式不传 exchangeAccountId(后端据 token 推导)
    assert "exchangeAccountId" not in body
    assert r["orderId"] == 200
