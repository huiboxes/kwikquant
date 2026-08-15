"""函数式策略 ctx + 数据类测试(撮合本地化后:place_order = 校验 + 排队,NEXT_BAR 由 event_loop 撮合)。"""

from __future__ import annotations

from decimal import Decimal
from unittest.mock import MagicMock

import pytest

from kwikquant_worker.backtest.matching import OrderIntent
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


# ---------- place_order 排队语义 ----------


def test_place_order_queues_intent_and_returns_none():
    """撮合本地化:place_order 校验后入队(不发 HTTP),返 None;意图字段完整。"""
    ctx = BacktestContext(MagicMock(), task_id=7, symbol="BTC/USDT")
    ret = ctx.place_order(side="BUY", order_type="MARKET", amount=0.1)  # float 金额边界
    assert ret is None
    intents = ctx.take_pending()
    assert intents == [
        OrderIntent(symbol="BTC/USDT", side="BUY", order_type="MARKET",
                    amount=Decimal("0.1"), price=None)
    ]


def test_take_pending_clears_queue():
    ctx = BacktestContext(MagicMock(), task_id=1, symbol="BTC/USDT")
    ctx.place_order(side="BUY", order_type="MARKET", amount="1")
    assert len(ctx.take_pending()) == 1
    assert ctx.take_pending() == []


def test_place_order_limit_carries_price():
    ctx = BacktestContext(MagicMock(), task_id=1, symbol="BTC/USDT")
    ctx.place_order(side="BUY", order_type="LIMIT", amount=Decimal("0.5"), price="3200")
    intent = ctx.take_pending()[0]
    assert intent.order_type == "LIMIT"
    assert intent.amount == Decimal("0.5")
    assert intent.price == Decimal("3200")


def test_place_order_amount_accepts_decimal_str_int_float():
    ctx = BacktestContext(MagicMock(), task_id=1, symbol="BTC/USDT")
    cases = [
        (Decimal("0.5"), Decimal("0.5")),
        ("0.25", Decimal("0.25")),
        (2, Decimal("2")),
        (0.01, Decimal("0.01")),  # float 经 str 转 Decimal(不丢精度路径)
    ]
    for amount, _ in cases:
        ctx.place_order(side="BUY", order_type="MARKET", amount=amount)
    assert [i.amount for i in ctx.take_pending()] == [expected for _, expected in cases]


@pytest.mark.parametrize("kwargs", [
    {"side": "HOLD", "order_type": "MARKET", "amount": "0.1"},
    {"side": "BUY", "order_type": "FOO", "amount": "0.1"},
    {"side": "BUY", "order_type": "MARKET", "amount": "0"},
    {"side": "BUY", "order_type": "MARKET", "amount": "-1"},
    {"side": "BUY", "order_type": "LIMIT", "amount": "0.1", "price": "0"},
    {"side": "BUY", "order_type": "LIMIT", "amount": "0.1", "price": "-5"},
    {"side": "BUY", "order_type": "MARKET", "amount": "abc"},
])
def test_place_order_validation_fails_closed(kwargs):
    """校验 fail-closed(对应原 Java 契约反序列化 400):非法参数抛 ValueError 且不入队。"""
    ctx = BacktestContext(MagicMock(), task_id=1, symbol="BTC/USDT")
    with pytest.raises(ValueError):
        ctx.place_order(**kwargs)
    assert ctx.take_pending() == []


def test_place_order_conditional_types_accepted_but_not_matched():
    """条件单可提交(契约向前兼容),撮合内核不主动触发(event_loop 产生 not-matched warning)。"""
    ctx = BacktestContext(MagicMock(), task_id=1, symbol="BTC/USDT")
    ctx.place_order(side="BUY", order_type="STOP_MARKET", amount="0.1")
    assert ctx.take_pending()[0].order_type == "STOP_MARKET"


# ---------- 持仓账本 ----------


def test_apply_fill_reverse_zeros_position():
    ctx = BacktestContext(MagicMock(), task_id=1, symbol="BTC/USDT")
    ctx._apply_fill(Fill(1, "BTC/USDT", "BUY", Decimal("100"), Decimal("0.1"), Decimal("0"), "", ""))
    ctx._apply_fill(Fill(2, "BTC/USDT", "SELL", Decimal("110"), Decimal("0.1"), Decimal("0"), "", ""))
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
