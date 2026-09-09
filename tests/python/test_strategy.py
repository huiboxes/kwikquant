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


def test_place_order_queues_intent_and_returns_ack():
    """撮合本地化:place_order 校验后入队(不发 HTTP),返 OrderAck(accepted=True);意图字段完整。

    NEXT_BAR 语义:成交发生在下一 bar,filled_qty/filled_price 提交时点恒 None
    (拒单异步进报告 warnings,不反映在回执)。
    """
    ctx = BacktestContext(MagicMock(), task_id=7, symbol="BTC/USDT")
    ack = ctx.place_order(side="BUY", order_type="MARKET", amount="0.1")
    assert ack.accepted is True
    assert ack.reason is None
    assert ack.filled_qty is None
    assert ack.filled_price is None
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


def test_place_order_amount_accepts_decimal_str_int():
    ctx = BacktestContext(MagicMock(), task_id=1, symbol="BTC/USDT")
    cases = [
        (Decimal("0.5"), Decimal("0.5")),
        ("0.25", Decimal("0.25")),
        (2, Decimal("2")),
    ]
    for amount, _ in cases:
        ctx.place_order(side="BUY", order_type="MARKET", amount=amount)
    assert [i.amount for i in ctx.take_pending()] == [expected for _, expected in cases]


def test_place_order_amount_rejects_float():
    """金额红线:float 在入口拒(TypeError)——残差(0.1+0.2)会踩库存闸门,官方不再兼容。"""
    ctx = BacktestContext(MagicMock(), task_id=1, symbol="BTC/USDT")
    with pytest.raises(TypeError, match="拒绝 float"):
        ctx.place_order(side="BUY", order_type="MARKET", amount=0.01)
    with pytest.raises(TypeError, match="拒绝 float"):
        ctx.place_order(side="BUY", order_type="LIMIT", amount="1", price=60000.5)
    assert ctx.take_pending() == []


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


def test_apply_fill_partial_sell_preserves_average_price():
    ctx = BacktestContext(MagicMock(), task_id=1, symbol="BTC/USDT")
    ctx._apply_fill(Fill(1, "BTC/USDT", "BUY", Decimal("100"), Decimal("10"), Decimal("0"), "", ""))
    ctx._apply_fill(Fill(2, "BTC/USDT", "SELL", Decimal("120"), Decimal("4"), Decimal("0"), "", ""))

    assert ctx.position("BTC/USDT") == Position("BTC/USDT", Decimal("6"), Decimal("100"))


def test_apply_fill_crossing_zero_uses_fill_price_for_reversed_position():
    ctx = BacktestContext(MagicMock(), task_id=1, symbol="BTC/USDT")
    ctx._apply_fill(Fill(1, "BTC/USDT", "BUY", Decimal("100"), Decimal("10"), Decimal("0"), "", ""))
    ctx._apply_fill(Fill(2, "BTC/USDT", "SELL", Decimal("120"), Decimal("15"), Decimal("0"), "", ""))

    assert ctx.position("BTC/USDT") == Position("BTC/USDT", Decimal("-5"), Decimal("120"))


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


def test_cancel_is_noop_in_backtest():
    """回测 cancel 为 no-op(未成交限价单单根 bar 自动过期),与 RunnerContext.cancel 同签名。"""
    ctx = BacktestContext(MagicMock(), 1)
    assert ctx.cancel(123) is None


# ---------- 统一契约新增面(params/close_position/equity/symbol 归一) ----------


def test_params_exposed_readonly():
    """任务 parameters 经 ctx.params 只读暴露(与 module.PARAMS 同源)。"""
    ctx = BacktestContext(MagicMock(), task_id=1, symbol="BTC/USDT", params={"fast": 5})
    assert ctx.params["fast"] == 5
    with pytest.raises(TypeError):
        ctx.params["fast"] = 9  # MappingProxyType 只读
    # 构造入参改动不泄漏(浅拷贝)
    raw = {"a": 1}
    ctx2 = BacktestContext(MagicMock(), task_id=1, params=raw)
    raw["b"] = 2
    assert "b" not in ctx2.params


def test_close_position_spot_queues_sell_with_ledger_qty():
    """SPOT 全平:用账本原值(Decimal)排队 SELL MARKET——从根上无精度残差。"""
    ctx = BacktestContext(MagicMock(), task_id=1, symbol="BTC/USDT")
    ctx._apply_fill(Fill(1, "BTC/USDT", "BUY", Decimal("100"), Decimal("0.3"), Decimal("0"), "", ""))
    ack = ctx.close_position()
    assert ack.accepted is True
    intent = ctx.take_pending()[0]
    assert intent.side == "SELL"
    assert intent.order_type == "MARKET"
    assert intent.amount == Decimal("0.3")


def test_close_position_perp_derives_effect_from_signed_qty():
    """PERP 全平:signed qty 派生 CLOSE_LONG/CLOSE_SHORT,amount=|qty|,side 不出现在入参。"""
    ctx = BacktestContext(MagicMock(), task_id=1, market_type="PERP", symbol="BTC/USDT:USDT")
    ctx._positions["BTC/USDT:USDT"] = Position("BTC/USDT:USDT", Decimal("-0.5"), Decimal("100"))
    ack = ctx.close_position()
    assert ack.accepted is True
    intent = ctx.take_pending()[0]
    assert intent.position_effect == "CLOSE_SHORT"
    assert intent.side == "BUY"  # 派生 side(平仓空头=买回)
    assert intent.amount == Decimal("0.5")


def test_close_position_flat_returns_no_position_ack():
    """无持仓 → accepted=False + NO_POSITION(不下单,显式可见,非异常)。"""
    ctx = BacktestContext(MagicMock(), task_id=1, symbol="BTC/USDT")
    ack = ctx.close_position()
    assert ack.accepted is False
    assert ack.reason == "NO_POSITION"
    assert ctx.take_pending() == []


def test_equity_and_available_cash_zero_before_bind():
    """未绑定引擎(run 前)→ equity/available_cash 返 0(不猜初始资金)。"""
    ctx = BacktestContext(MagicMock(), task_id=1, symbol="BTC/USDT")
    assert ctx.equity() == Decimal(0)
    assert ctx.available_cash() == Decimal(0)


def test_position_defaults_to_bound_symbol_and_rejects_foreign():
    """position/history/place_order/close_position 的 symbol 可省(绑定值);显式传异 symbol 拒。"""
    ctx = BacktestContext(MagicMock(), task_id=1, symbol="BTC/USDT")
    ctx._apply_fill(Fill(1, "BTC/USDT", "BUY", Decimal("100"), Decimal("1"), Decimal("0"), "", ""))
    assert ctx.position().qty == Decimal("1")  # 缺省=绑定 symbol
    with pytest.raises(ValueError, match="不接受其他 symbol"):
        ctx.position("ETH/USDT")
    with pytest.raises(ValueError, match="不接受其他 symbol"):
        ctx.history("close", 5, symbol="ETH/USDT")
    with pytest.raises(ValueError, match="不接受其他 symbol"):
        ctx.place_order(symbol="ETH/USDT", side="BUY", order_type="MARKET", amount="1")
