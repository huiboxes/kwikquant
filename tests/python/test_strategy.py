"""函数式策略 ctx + 数据类测试(回测数据获取重构)。"""

from __future__ import annotations

from decimal import Decimal
from unittest.mock import MagicMock

import pytest

from kwikquant_worker.strategy import BacktestContext, Bar, Fill, Position


def _kline(t: str, c: str) -> dict:
    return {"timestamp": t, "open": c, "high": c, "low": c, "close": c, "volume": "1"}


def test_bar_holds_floats():
    b = Bar(timestamp="t", open=1.0, high=2.0, low=0.5, close=1.5, volume=100.0)
    assert b.open + b.close == 2.5
    assert isinstance(b.close, float)


def test_history_returns_last_n_including_current():
    ctx = BacktestContext(MagicMock(), task_id=1, symbol="BTC/USDT")
    ctx.set_klines([_kline("t1", "1"), _kline("t2", "2"), _kline("t3", "3")])
    ctx.set_index(2)
    assert ctx.history("close", 2) == [2.0, 3.0]


def test_history_warmup_returns_partial():
    ctx = BacktestContext(MagicMock(), task_id=1)
    ctx.set_klines([_kline("t1", "1"), _kline("t2", "2")])
    ctx.set_index(0)
    assert ctx.history("close", 20) == [1.0]


def test_history_before_set_returns_empty():
    assert BacktestContext(MagicMock(), task_id=1).history("close", 5) == []


def test_history_coerces_str_to_float():
    ctx = BacktestContext(MagicMock(), task_id=1)
    ctx.set_klines([_kline("t1", "42000.5")])
    ctx.set_index(0)
    assert ctx.history("close", 1) == [42000.5]


def test_history_field_selects_open():
    ctx = BacktestContext(MagicMock(), task_id=1)
    ctx.set_klines([{"timestamp": "t", "open": "10", "high": "20", "low": "5",
                      "close": "15", "volume": "1"}])
    ctx.set_index(0)
    assert ctx.history("open", 1) == [10.0]


def test_place_order_requires_snapshot():
    ctx = BacktestContext(MagicMock(), task_id=1, symbol="BTC/USDT")
    with pytest.raises(ValueError):
        ctx.place_order(side="BUY", order_type="MARKET", amount="0.1")


def test_place_order_calls_submit_backtest_and_updates_position():
    client = MagicMock()
    client.trade.submit_backtest.return_value = {
        "orderId": 9, "symbol": "BTC/USDT", "side": "BUY",
        "price": "42000", "qty": "0.1", "fee": "0.42",
        "feeCurrency": "USDT", "filledAt": "2024-01-01T00:00:00Z",
    }
    ctx = BacktestContext(client, task_id=7, symbol="BTC/USDT")
    ctx.set_snapshot({"timestamp": "t", "open": 1, "high": 1, "low": 1, "close": 1, "volume": 1})
    fill = ctx.place_order(side="BUY", order_type="MARKET", amount=0.1)  # float 金额边界
    assert fill is not None
    assert isinstance(fill, Fill)
    assert fill.order_id == 9
    assert fill.price == Decimal("42000")
    assert fill.qty == Decimal("0.1")
    call = client.trade.submit_backtest.call_args
    assert call.args[0] == 7
    assert call.kwargs["amount"] == "0.1"  # _bd(float) -> str
    assert call.kwargs["symbol"] == "BTC/USDT"
    pos = ctx.position("BTC/USDT")
    assert pos.qty == Decimal("0.1")
    assert pos.avg_price == Decimal("42000")


def test_place_order_amount_accepts_decimal_and_str():
    client = MagicMock()
    client.trade.submit_backtest.return_value = {
        "orderId": 1, "price": "100", "qty": "0.5", "fee": "0",
        "feeCurrency": "", "symbol": "BTC/USDT", "side": "BUY", "filledAt": "",
    }
    ctx = BacktestContext(client, task_id=1, symbol="BTC/USDT")
    ctx.set_snapshot({"timestamp": "t", "open": 1, "high": 1, "low": 1, "close": 1, "volume": 1})
    ctx.place_order(side="BUY", order_type="LIMIT", amount=Decimal("0.5"), price="3200")
    kw = client.trade.submit_backtest.call_args.kwargs
    assert kw["amount"] == "0.5"
    assert kw["price"] == "3200"
    assert kw["order_type"] == "LIMIT"


def test_place_order_returns_none_when_unmatched():
    client = MagicMock()
    client.trade.submit_backtest.return_value = None
    ctx = BacktestContext(client, task_id=1, symbol="BTC/USDT")
    ctx.set_snapshot({"timestamp": "t", "open": 1, "high": 1, "low": 1, "close": 1, "volume": 1})
    assert ctx.place_order(side="BUY", order_type="LIMIT", amount="0.1", price="100") is None


def test_apply_fill_reverse_zeros_position():
    client = MagicMock()
    client.trade.submit_backtest.side_effect = [
        {"orderId": 1, "price": "100", "qty": "0.1", "fee": "0", "feeCurrency": "",
         "symbol": "BTC/USDT", "side": "BUY", "filledAt": ""},
        {"orderId": 2, "price": "110", "qty": "0.1", "fee": "0", "feeCurrency": "",
         "symbol": "BTC/USDT", "side": "SELL", "filledAt": ""},
    ]
    ctx = BacktestContext(client, task_id=1, symbol="BTC/USDT")
    ctx.set_snapshot({"timestamp": "t", "open": 1, "high": 1, "low": 1, "close": 1, "volume": 1})
    ctx.place_order(side="BUY", order_type="MARKET", amount="0.1")
    ctx.place_order(side="SELL", order_type="MARKET", amount="0.1")
    assert ctx.position("BTC/USDT").qty == Decimal("0")


def test_position_default_zero():
    ctx = BacktestContext(MagicMock(), task_id=1, symbol="BTC/USDT")
    p = ctx.position("BTC/USDT")
    assert p == Position(symbol="BTC/USDT", qty=Decimal(0), avg_price=Decimal(0))


def test_symbol_property():
    assert BacktestContext(MagicMock(), task_id=1, symbol="ETH/USDT").symbol == "ETH/USDT"


def test_log_writes_to_stderr(capsys):
    ctx = BacktestContext(MagicMock(), task_id=1)
    ctx.log("金叉做多 fast=42000 slow=41000")
    assert "金叉做多" in capsys.readouterr().err
