"""TradeService — §4.2 request/response schema 合约测试(Wave 8 §3.3/§3.4)。"""

from __future__ import annotations

import json
from decimal import Decimal

import httpx

from kwikquant.client import Auth, Client


def test_submit_backtest_serializes_amount_price_and_snapshot_as_strings(make_transport, envelope):
    captured = {}

    def _handler(req: httpx.Request):
        captured["path"] = str(req.url.path)
        captured["header"] = req.headers.get("X-Worker-Token")
        captured["body"] = json.loads(req.content)
        return httpx.Response(200, content=envelope({
            "orderId": 7, "accountId": 0, "symbol": "BTC/USDT", "side": "BUY",
            "price": "42150", "qty": "0.1", "fee": "0.4215",
            "feeCurrency": "USDT", "liquidity": "taker",
            "externalFillId": "abc", "filledAt": "2024-01-15T08:00:00Z",
        }))

    tr = make_transport([("POST", "/api/v1/backtests/11/orders", _handler)])
    with Client("http://kw", Auth.service_token("wt-1"), transport=tr) as c:
        fill = c.trade.submit_backtest(
            11,
            symbol="BTC/USDT", side="BUY", order_type="MARKET",
            amount=Decimal("0.1"), price=None,
            snapshot={
                "timestamp": "2024-01-15T08:00:00Z",
                "open": Decimal("42100"), "high": 42200, "low": "42050",
                "close": 42150.0, "volume": Decimal("123.4"),
            },
        )

    assert captured["path"] == "/api/v1/backtests/11/orders"
    assert captured["header"] == "wt-1"
    body = captured["body"]
    # 契约:BigDecimal 全部字符串
    assert body["symbol"] == "BTC/USDT"
    assert body["side"] == "BUY"
    assert body["orderType"] == "MARKET"
    assert body["amount"] == "0.1"
    assert body["price"] is None
    snap = body["snapshot"]
    assert snap["timestamp"] == "2024-01-15T08:00:00Z"
    assert snap["open"] == "42100" and snap["close"] == "42150.0"
    assert isinstance(snap["volume"], str)

    assert fill["orderId"] == 7 and fill["price"] == "42150"


def test_submit_backtest_return_none_on_empty_response(make_transport, envelope):
    def _handler(req):
        return httpx.Response(200, content=envelope(None))

    tr = make_transport([("POST", "/api/v1/backtests/1/orders", _handler)])
    with Client("http://kw", Auth.service_token("t"), transport=tr) as c:
        fill = c.trade.submit_backtest(
            1, symbol="BTC/USDT", side="BUY", order_type="LIMIT", amount="0.1",
            price="40000", snapshot={"timestamp": "2024-01-01T00:00:00Z",
                                     "open": "1", "high": "1", "low": "1", "close": "1"},
        )
    assert fill is None


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
