"""DataService — 历史 K 线 SDK 方法(kwikquant.data)。"""

from __future__ import annotations

from unittest.mock import MagicMock

from kwikquant.data import DataService


def _kline(t: str = "2024-01-01T00:00:00Z") -> dict:
    return {
        "exchange": "OKX",
        "marketType": "SPOT",
        "symbol": "BTC/USDT",
        "interval": "1h",
        "openTime": t,
        "open": "50000",
        "high": "50100",
        "low": "49900",
        "close": "50050",
        "volume": "12.5",
    }


def _client_returning(data) -> MagicMock:
    """client.get 返 envelope 解包后的 {"data": data}(见 client._handle_response:list 被包成 {"data": [...]})。"""
    client = MagicMock()
    client.get.return_value = {"data": data}
    return client


def test_ohlcv_calls_market_klines_with_correct_params():
    client = _client_returning([_kline()])
    svc = DataService(client)

    result = svc.ohlcv("OKX", "SPOT", "BTC/USDT", "1h", limit=5)

    assert len(result) == 1
    assert result[0]["openTime"] == "2024-01-01T00:00:00Z"
    client.get.assert_called_once_with(
        "/api/v1/market/klines",
        params={
            "exchange": "OKX",
            "marketType": "SPOT",
            "symbol": "BTC/USDT",
            "interval": "1h",
            "limit": 5,
        },
    )


def test_ohlcv_before_param_passed_when_given():
    # before(ISO-8601)往前翻页:open_time < before 的最近 N 根
    client = _client_returning([])
    svc = DataService(client)

    svc.ohlcv("OKX", "SPOT", "BTC/USDT", "1h", limit=10, before="2026-07-17T10:00:00Z")

    assert client.get.call_args.kwargs["params"]["before"] == "2026-07-17T10:00:00Z"


def test_ohlcv_before_omitted_when_none():
    # before=None(默认)不加该 query 参数——runner 预填拉最近 N 根
    client = _client_returning([])
    svc = DataService(client)

    svc.ohlcv("OKX", "SPOT", "BTC/USDT", "1h")

    assert "before" not in client.get.call_args.kwargs["params"]


def test_ohlcv_empty_result_returns_empty():
    client = _client_returning([])
    svc = DataService(client)
    assert svc.ohlcv("OKX", "SPOT", "BTC/USDT", "1h") == []


def test_ohlcv_non_list_data_returns_empty():
    # 防御:响应 data 非 list(异常 envelope/None)→ [],不抛
    client = MagicMock()
    client.get.return_value = {"data": None}
    svc = DataService(client)
    assert svc.ohlcv("OKX", "SPOT", "BTC/USDT", "1h") == []
