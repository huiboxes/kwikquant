"""TradeService — request/response schema 合约测试。

撮合本地化后回测通道仅剩 get_klines(数据)+ report_progress(进度);
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


def test_submit_perp_omits_side_when_none(make_transport, envelope):
    """PERP side=None → payload 不带 side 键(服务端由 positionEffect 派生,单一真相源)。"""
    captured = {}

    def _handler(req: httpx.Request):
        captured["body"] = json.loads(req.content)
        return httpx.Response(200, content=envelope({"orderId": 201}))

    tr = make_transport([("POST", "/api/v1/orders", _handler)])
    with Client("http://kw", Auth.service_token("t"), transport=tr) as c:
        c.trade.submit(
            symbol="BTC/USDT:USDT",
            order_type="MARKET",
            amount="0.1",
            market_type="PERP",
            leverage=10,
            margin_mode="ISOLATED",
            position_effect="OPEN_SHORT",
        )
    assert "side" not in captured["body"]
    assert captured["body"]["positionEffect"] == "OPEN_SHORT"


def test_get_funding_rates_maps_camel_to_snake(make_transport, envelope):
    """PERP 资金费序列拉取:GET /backtests/{id}/funding-rates,marketType 固定 PERP,
    camelCase → snake_case 映射(event_loop/perp_ledger 消费)。"""
    seen = {}

    def _handler(req):
        seen["path"] = str(req.url.path)
        seen["query"] = dict(req.url.params)
        return httpx.Response(
            200,
            content=envelope(
                [
                    {
                        "fundingTime": "2024-01-01T08:00:00Z",
                        "settledRate": "0.0001",
                        "intervalSeconds": 28800,
                        "markPrice": "60000",
                        "source": "EXCHANGE",
                    },
                    {
                        "fundingTime": "2024-01-01T16:00:00Z",
                        "settledRate": "-0.0002",
                        "intervalSeconds": 28800,
                        "markPrice": None,
                        "source": "PROXY_BINANCE",
                    },
                ]
            ),
        )

    tr = make_transport([("GET", "/api/v1/backtests/9/funding-rates", _handler)])
    with Client("http://kw", Auth.service_token("wt-1"), transport=tr) as c:
        rows = c.trade.get_funding_rates(
            9, exchange="OKX", symbol="BTC/USDT",
            start="2024-01-01T00:00:00Z", end="2024-01-02T00:00:00Z",
        )

    assert seen["path"] == "/api/v1/backtests/9/funding-rates"
    assert seen["query"] == {
        "exchange": "OKX",
        "marketType": "PERP",
        "symbol": "BTC/USDT",
        "start": "2024-01-01T00:00:00Z",
        "end": "2024-01-02T00:00:00Z",
    }
    assert rows == [
        {
            "funding_time": "2024-01-01T08:00:00Z",
            "settled_rate": "0.0001",
            "interval_seconds": 28800,
            "mark_price": "60000",
            "source": "EXCHANGE",
        },
        {
            "funding_time": "2024-01-01T16:00:00Z",
            "settled_rate": "-0.0002",
            "interval_seconds": 28800,
            "mark_price": None,
            "source": "PROXY_BINANCE",
        },
    ]


def test_get_funding_rates_empty_non_list(make_transport, envelope):
    """data 非 list(防御)→ 返 [](缺期判定在 data_loader,空序列 fail-closed)。"""

    def _handler(req):
        return httpx.Response(200, content=envelope(None))

    tr = make_transport([("GET", "/api/v1/backtests/9/funding-rates", _handler)])
    with Client("http://kw", Auth.service_token("wt-1"), transport=tr) as c:
        assert c.trade.get_funding_rates(
            9, exchange="OKX", symbol="BTC/USDT", start="s", end="e"
        ) == []
