"""Strategy 基类 + BacktestContext 单元测试(Wave 8 §3.5)。"""

from __future__ import annotations

from decimal import Decimal
from unittest.mock import MagicMock

import pytest

from kwikquant_worker.strategy import (
    BacktestContext,
    Bar,
    Fill,
    Strategy,
    StrategyContext,
)


class _StubCtx(StrategyContext):
    def __init__(self):
        self.calls = []

    def submit_order(self, **kwargs):
        self.calls.append(kwargs)
        return Fill(
            order_id=1, symbol=kwargs["symbol"], side=kwargs["side"],
            price=Decimal("100"), qty=Decimal(str(kwargs["amount"])),
            fee=Decimal(0), fee_currency="USDT", filled_at="",
        )

    def position(self, symbol):
        from kwikquant_worker.strategy import Position
        return Position(symbol=symbol, qty=Decimal(0), avg_price=Decimal(0))


def test_strategy_buy_delegates_to_ctx_with_defaults():
    ctx = _StubCtx()
    s = Strategy(ctx=ctx, default_symbol="BTC/USDT")
    s.buy(amount="0.1")
    assert ctx.calls[0]["symbol"] == "BTC/USDT"
    assert ctx.calls[0]["side"] == "BUY"
    assert ctx.calls[0]["order_type"] == "MARKET"
    assert ctx.calls[0]["amount"] == Decimal("0.1")


def test_strategy_sell_uses_limit_and_price():
    ctx = _StubCtx()
    s = Strategy(ctx=ctx, default_symbol="ETH/USDT")
    s.sell(amount=Decimal("0.5"), price="3200", order_type="LIMIT")
    assert ctx.calls[0]["order_type"] == "LIMIT"
    assert ctx.calls[0]["price"] == Decimal("3200")


def test_strategy_missing_symbol_raises():
    ctx = _StubCtx()
    s = Strategy(ctx=ctx)  # no default_symbol
    with pytest.raises(ValueError):
        s.buy(amount="0.1")


def test_strategy_missing_amount_raises():
    ctx = _StubCtx()
    s = Strategy(ctx=ctx, default_symbol="BTC/USDT")
    with pytest.raises(ValueError):
        s.buy(amount=None)


def test_strategy_sell_all_uses_position_qty():
    from kwikquant_worker.strategy import Position

    ctx = _StubCtx()
    ctx.position = lambda sym: Position(sym, Decimal("0.3"), Decimal("100"))  # type: ignore
    s = Strategy(ctx=ctx, default_symbol="BTC/USDT")
    s.sell_all()
    assert ctx.calls[0]["amount"] == Decimal("0.3")


def test_strategy_sell_all_zero_position_returns_none():
    ctx = _StubCtx()
    s = Strategy(ctx=ctx, default_symbol="BTC/USDT")
    result = s.sell_all()
    assert result is None
    assert ctx.calls == []


def test_backtest_context_submit_requires_snapshot():
    client = MagicMock()
    ctx = BacktestContext(client, task_id=1)
    with pytest.raises(ValueError):
        ctx.submit_order(symbol="BTC/USDT", side="BUY", order_type="MARKET", amount=Decimal("0.1"))


def test_backtest_context_submit_calls_client_and_updates_position():
    client = MagicMock()
    client.trade.submit_backtest.return_value = {
        "orderId": 9, "symbol": "BTC/USDT", "side": "BUY",
        "price": "42000", "qty": "0.1", "fee": "0.42",
        "feeCurrency": "USDT", "filledAt": "2024-01-01T00:00:00Z",
    }
    ctx = BacktestContext(client, task_id=7)
    ctx.set_snapshot({"timestamp": "2024-01-01T00:00:00Z", "open": Decimal("42000"),
                      "high": Decimal("42100"), "low": Decimal("41900"),
                      "close": Decimal("42050"), "volume": Decimal("10")})
    fill = ctx.submit_order(symbol="BTC/USDT", side="BUY", order_type="MARKET",
                            amount=Decimal("0.1"))
    assert fill is not None
    assert fill.order_id == 9
    assert client.trade.submit_backtest.called
    # POST 目标 taskId=7,与初始化一致
    call = client.trade.submit_backtest.call_args
    assert call.args[0] == 7
    pos = ctx.position("BTC/USDT")
    assert pos.qty == Decimal("0.1")
    assert pos.avg_price == Decimal("42000")


def test_backtest_context_returns_none_when_unmatched():
    client = MagicMock()
    client.trade.submit_backtest.return_value = None
    ctx = BacktestContext(client, task_id=1)
    ctx.set_snapshot({"timestamp": "2024-01-01T00:00:00Z", "open": "1", "high": "1",
                      "low": "1", "close": "1"})
    fill = ctx.submit_order(symbol="BTC/USDT", side="BUY", order_type="LIMIT",
                            amount=Decimal("0.1"), price=Decimal("100"))
    assert fill is None


def test_backtest_context_apply_fill_reverse_zeros_position():
    client = MagicMock()
    client.trade.submit_backtest.side_effect = [
        {"orderId": 1, "price": "100", "qty": "0.1", "fee": "0", "feeCurrency": "",
         "symbol": "BTC/USDT", "side": "BUY", "filledAt": ""},
        {"orderId": 2, "price": "110", "qty": "0.1", "fee": "0", "feeCurrency": "",
         "symbol": "BTC/USDT", "side": "SELL", "filledAt": ""},
    ]
    ctx = BacktestContext(client, task_id=1)
    ctx.set_snapshot({"timestamp": "t1", "open": "1", "high": "1", "low": "1", "close": "1"})
    ctx.submit_order(symbol="BTC/USDT", side="BUY", order_type="MARKET", amount=Decimal("0.1"))
    ctx.set_snapshot({"timestamp": "t2", "open": "1", "high": "1", "low": "1", "close": "1"})
    ctx.submit_order(symbol="BTC/USDT", side="SELL", order_type="MARKET", amount=Decimal("0.1"))
    pos = ctx.position("BTC/USDT")
    assert pos.qty == Decimal("0")


def test_bar_dataclass_holds_decimals():
    b = Bar(timestamp="t", open=Decimal("1"), high=Decimal("2"),
            low=Decimal("0.5"), close=Decimal("1.5"), volume=Decimal("100"))
    assert b.open + b.close == Decimal("2.5")
