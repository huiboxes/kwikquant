"""data_loader — 调 Java REST 拉 K 线(回测数据获取重构,废 PG 直连)。"""

from __future__ import annotations

from unittest.mock import MagicMock

import pytest

from kwikquant_worker.data_loader import load_klines


def _kline(t: str = "2024-01-01T00:00:00Z") -> dict:
    return {
        "timestamp": t,
        "open": "50000",
        "high": "50100",
        "low": "49900",
        "close": "50050",
        "volume": "12.5",
    }


def test_load_klines_calls_java_rest():
    client = MagicMock()
    client.trade().get_klines.return_value = [_kline()]

    result = load_klines(
        client,
        42,
        exchange="OKX",
        market_type="SPOT",
        symbol="BTC/USDT",
        interval="1h",
        start="2024-01-01T00:00:00Z",
        end="2024-01-02T00:00:00Z",
    )

    assert len(result) == 1
    assert result[0]["timestamp"] == "2024-01-01T00:00:00Z"
    client.trade().get_klines.assert_called_once_with(
        42,
        exchange="OKX",
        market_type="SPOT",
        symbol="BTC/USDT",
        interval="1h",
        start="2024-01-01T00:00:00Z",
        end="2024-01-02T00:00:00Z",
    )


def test_load_klines_empty_returns_empty():
    # 空结果不抛(上层 worker_server 据此 exit 2 → Java markFailed 7304)
    client = MagicMock()
    client.trade().get_klines.return_value = []

    result = load_klines(
        client, 1, exchange="OKX", market_type="SPOT",
        symbol="BTC/USDT", interval="1h", start="s", end="e",
    )

    assert result == []


def test_load_klines_propagates_client_error():
    # REST 网络错/4xx 透传抛(上层 exit 1)
    client = MagicMock()
    client.trade().get_klines.side_effect = RuntimeError("network down")

    with pytest.raises(RuntimeError):
        load_klines(
            client, 1, exchange="OKX", market_type="SPOT",
            symbol="BTC/USDT", interval="1h", start="s", end="e",
        )
