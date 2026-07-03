"""§8 JSON 适配器测试(Wave 8 §3.4 §7.7)。"""

from __future__ import annotations

from decimal import Decimal

from kwikquant.adapters import to_section8_from_pandas


def test_to_section8_shape():
    result = to_section8_from_pandas(
        name="my-run",
        params={"fast": 10, "slow": 30},
        symbol="BTC/USDT",
        timeframe="1h",
        period_start="2024-01-01T00:00:00Z",
        period_end="2024-01-31T23:00:00Z",
        trades=[
            {"time": "2024-01-01T00:00:00Z", "side": "buy", "price": Decimal("42150"),
             "amount": Decimal("0.1"), "fee": Decimal("0.42")},
            {"time": "2024-01-15T12:00:00Z", "side": "sell", "price": 45000, "amount": 0.1},
        ],
        equity_curve=[
            {"time": "2024-01-01T00:00:00Z", "equity": Decimal("100000")},
            {"time": "2024-01-31T23:00:00Z", "equity": Decimal("102850")},
        ],
    )
    assert result["name"] == "my-run"
    assert result["period"] == {"start": "2024-01-01T00:00:00Z", "end": "2024-01-31T23:00:00Z"}
    assert result["trades"][0]["price"] == "42150"
    assert result["trades"][0]["fee"] == "0.42"
    assert result["trades"][1]["fee"] == "0"  # 默认
    assert result["equity_curve"][0]["equity"] == "100000"
    assert result["metrics"] == {}  # Java 重算
