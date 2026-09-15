"""策略契约统一差分测试 — context.py 单一真相源 + 三 ctx 同构锁死。

契约漂移由本文件拦截:同一输入矩阵在三个 ctx(单标的回测/组合回测/runner)上必须
行为一致(要么都受理、要么抛同型异常)。normalize_order/to_decimal/clamp_dust_close
是共享单源,ctx 层只做 symbol 归一与排队/HTTP 差异。runner 的 HTTP 形态细节在
test_runner_context.py。
"""

from __future__ import annotations

from decimal import Decimal
from unittest.mock import MagicMock

import pytest

from kwikquant_worker.context import (
    DUST_TOLERANCE,
    OrderAck,
    StrategyContext,
    clamp_dust_close,
    normalize_order,
    to_decimal,
    to_leverage,
)
from kwikquant_worker.portfolio import PortfolioContext
from kwikquant_worker.runner_context import RunnerContext
from kwikquant_worker.strategy import BacktestContext


# ---------- to_decimal / to_leverage 边界 ----------


def test_to_decimal_accepts_decimal_str_int():
    assert to_decimal(Decimal("0.5"), "amount") == Decimal("0.5")
    assert to_decimal("0.25", "amount") == Decimal("0.25")
    assert to_decimal(2, "amount") == Decimal("2")


def test_to_decimal_rejects_float_with_guidance():
    """金额红线:float 拒(TypeError),消息带修复指引(str/Decimal)。"""
    with pytest.raises(TypeError, match="拒绝 float") as e:
        to_decimal(0.1, "amount")
    assert "Decimal('0.01')" in str(e.value) or "str/Decimal" in str(e.value)


def test_to_decimal_invalid_value_raises():
    with pytest.raises(ValueError, match="非法"):
        to_decimal("abc", "amount")


def test_to_leverage_rejects_float_and_coerces_str():
    assert to_leverage(10) == 10
    assert to_leverage("10") == 10
    with pytest.raises(ValueError, match="float 拒绝"):
        to_leverage(10.0)
    with pytest.raises(ValueError, match="非法"):
        to_leverage("abc")


# ---------- normalize_order 共享校验单源 ----------


def test_normalize_spot_derives_clean_intent():
    o = normalize_order(market_type="SPOT", side="BUY", order_type="MARKET", amount="0.1")
    assert (o.side, o.order_type, o.amount, o.price) == ("BUY", "MARKET", Decimal("0.1"), None)
    assert o.position_effect is None and o.leverage is None and o.margin_mode is None


def test_normalize_perp_derives_side_from_effect():
    """PERP side 单源派生(与 acceptance.EFFECT_TO_SIDE / Java Order.validate 同一张表)。"""
    cases = {
        "OPEN_LONG": "BUY",
        "OPEN_SHORT": "SELL",
        "CLOSE_LONG": "SELL",
        "CLOSE_SHORT": "BUY",
    }
    for effect, side in cases.items():
        o = normalize_order(market_type="PERP", order_type="MARKET", amount="1", position_effect=effect)
        assert o.side == side
        assert o.position_effect == effect


def test_normalize_perp_leverage_str_coerced_margin_mode_validated():
    o = normalize_order(
        market_type="PERP", order_type="MARKET", amount="1",
        position_effect="OPEN_LONG", leverage="10", margin_mode="ISOLATED",
    )
    assert o.leverage == 10 and o.margin_mode == "ISOLATED"
    with pytest.raises(ValueError, match="margin_mode 非法"):
        normalize_order(
            market_type="PERP", order_type="MARKET", amount="1",
            position_effect="OPEN_LONG", margin_mode="FULL",
        )


@pytest.mark.parametrize("kwargs,exc", [
    # SPOT:side 必填/枚举,合约字段禁传(接受性规则 19 同构)
    ({"market_type": "SPOT", "order_type": "MARKET", "amount": "1"}, ValueError),
    ({"market_type": "SPOT", "side": "HOLD", "order_type": "MARKET", "amount": "1"}, ValueError),
    ({"market_type": "SPOT", "side": "BUY", "order_type": "MARKET", "amount": "1", "position_effect": "OPEN_LONG"}, ValueError),
    ({"market_type": "SPOT", "side": "BUY", "order_type": "MARKET", "amount": "1", "leverage": 10}, ValueError),
    # PERP:effect 必填四向,side 禁传(双源矛盾入口消灭)
    ({"market_type": "PERP", "order_type": "MARKET", "amount": "1"}, ValueError),
    ({"market_type": "PERP", "side": "BUY", "order_type": "MARKET", "amount": "1", "position_effect": "OPEN_LONG"}, ValueError),
    ({"market_type": "PERP", "order_type": "MARKET", "amount": "1", "position_effect": "FLIP"}, ValueError),
    # 通用:order_type 枚举 / amount 值域 / float 拒
    ({"market_type": "SPOT", "side": "BUY", "order_type": "FOK", "amount": "1"}, ValueError),
    ({"market_type": "SPOT", "side": "BUY", "order_type": "MARKET", "amount": "0"}, ValueError),
    ({"market_type": "SPOT", "side": "BUY", "order_type": "MARKET", "amount": "-1"}, ValueError),
    ({"market_type": "SPOT", "side": "BUY", "order_type": "MARKET", "amount": 0.01}, TypeError),
    ({"market_type": "SPOT", "side": "BUY", "order_type": "LIMIT", "amount": "1", "price": 100.5}, TypeError),
])
def test_normalize_fail_closed_matrix(kwargs, exc):
    with pytest.raises(exc):
        normalize_order(**kwargs)


def test_normalize_spot_conditional_types_accepted():
    """条件单可提交(契约向前兼容),回测内核不主动触发(matching-spec §3)。"""
    for t in ("STOP_MARKET", "STOP_LIMIT", "TAKE_PROFIT_MARKET", "TAKE_PROFIT_LIMIT", "TRAILING_STOP"):
        o = normalize_order(market_type="SPOT", side="BUY", order_type=t, amount="1")
        assert o.order_type == t


# ---------- 回测两 ctx 差分(同一矩阵同型行为) ----------


def _backtest_ctxs(market_type="SPOT"):
    """单标的 + 组合两个回测 ctx(组合绑定单 symbol 列表便于同矩阵调用)。"""
    return [
        BacktestContext(MagicMock(), 1, market_type=market_type, symbol="BTC/USDT"),
        PortfolioContext(MagicMock(), 1, market_type=market_type, symbols=["BTC/USDT"]),
    ]


def _place(ctx, **kw):
    """组合 ctx 需显式 symbol;单标的可省——差分矩阵统一显式传。"""
    return ctx.place_order(symbol="BTC/USDT", **kw)


@pytest.mark.parametrize("kwargs,exc", [
    ({"side": "BUY", "order_type": "MARKET", "amount": 0.01}, TypeError),
    ({"side": "HOLD", "order_type": "MARKET", "amount": "1"}, ValueError),
    ({"side": "BUY", "order_type": "FOK", "amount": "1"}, ValueError),
    ({"side": "BUY", "order_type": "MARKET", "amount": "0"}, ValueError),
    ({"side": "BUY", "order_type": "MARKET", "amount": "1", "position_effect": "OPEN_LONG"}, ValueError),
])
def test_spot_ctx_place_order_differential_rejects(kwargs, exc):
    """SPOT 非法入参:两个回测 ctx 抛同型异常且都不入队(共享 normalize 单源)。"""
    for ctx in _backtest_ctxs():
        with pytest.raises(exc):
            _place(ctx, **kwargs)
        assert ctx.take_pending() == []


def test_spot_ctx_place_order_differential_accepts():
    for ctx in _backtest_ctxs():
        ack = _place(ctx, side="BUY", order_type="MARKET", amount="0.1")
        assert isinstance(ack, OrderAck) and ack.accepted
        intents = ctx.take_pending()
        assert len(intents) == 1
        assert intents[0].symbol == "BTC/USDT"
        assert intents[0].amount == Decimal("0.1")


def test_perp_backtest_ctx_accepts_four_effects_and_rejects_side():
    """PERP 单标的 ctx:四向受理(side 派生);side 显式传拒(双源矛盾)。"""
    ctx = BacktestContext(MagicMock(), 1, market_type="PERP", symbol="BTC/USDT:USDT")
    for effect in ("OPEN_LONG", "OPEN_SHORT", "CLOSE_LONG", "CLOSE_SHORT"):
        ack = ctx.place_order(order_type="MARKET", amount="1", position_effect=effect)
        assert ack.accepted
    assert len(ctx.take_pending()) == 4
    with pytest.raises(ValueError, match="禁传 side"):
        ctx.place_order(side="BUY", order_type="MARKET", amount="1", position_effect="OPEN_LONG")


def test_portfolio_ctx_requires_explicit_symbol():
    """组合 ctx 无单一绑定交易对:缺省 symbol 即契约违规(fail-closed)。"""
    ctx = PortfolioContext(MagicMock(), 1, symbols=["BTC/USDT"])
    for call in (
        lambda: ctx.place_order(side="BUY", order_type="MARKET", amount="1"),
        lambda: ctx.position(),
        lambda: ctx.history("close", 5),
        lambda: ctx.close_position(),
    ):
        with pytest.raises(ValueError, match="必须显式传 symbol"):
            call()


def test_portfolio_ctx_rejects_foreign_symbol():
    ctx = PortfolioContext(MagicMock(), 1, symbols=["BTC/USDT"])
    with pytest.raises(ValueError, match="不在本任务标的列表"):
        ctx.place_order(symbol="ZZZ/USDT", side="BUY", order_type="MARKET", amount="1")


# ---------- 三 ctx 差分(含 runner) ----------


def _all_ctxs(market_type="SPOT"):
    return [
        BacktestContext(MagicMock(), 1, market_type=market_type, symbol="BTC/USDT"),
        PortfolioContext(MagicMock(), 1, market_type=market_type, symbols=["BTC/USDT"]),
        RunnerContext(MagicMock(), 1, exchange="OKX", market_type=market_type, symbol="BTC/USDT"),
    ]


@pytest.mark.parametrize("kwargs,exc", [
    ({"side": "BUY", "order_type": "MARKET", "amount": 0.01}, TypeError),  # float 拒
    ({"side": "HOLD", "order_type": "MARKET", "amount": "1"}, ValueError),
    ({"side": "BUY", "order_type": "FOK", "amount": "1"}, ValueError),
    ({"side": "BUY", "order_type": "MARKET", "amount": "0"}, ValueError),
    ({"order_type": "MARKET", "amount": "1"}, ValueError),  # SPOT side 必填
    ({"side": "BUY", "order_type": "MARKET", "amount": "1", "leverage": 10}, ValueError),
])
def test_three_ctx_reject_matrix_differential(kwargs, exc):
    """同一非法矩阵三个 ctx 抛同型异常(共享 normalize 单源);runner 校验先于 HTTP,不发请求。"""
    ctxs = _all_ctxs()
    for ctx in ctxs:
        with pytest.raises(exc):
            ctx.place_order(symbol="BTC/USDT", **kwargs)
    # runner 未触 HTTP(normalize 在 try 块之前,契约违规不发请求)
    assert ctxs[2]._client.trade.submit.call_count == 0


def test_three_ctx_perp_contract_differential():
    """PERP 契约三运行时同构:effect 必填四向、side 禁传(双源矛盾入口消灭)、amount 拒 float。
    组合 ctx 纳入(perp-backtest-spec §10.9:组合 PERP 已入 PERP 意图差分集)——显式传 symbol。"""
    b = BacktestContext(MagicMock(), 1, market_type="PERP", symbol="BTC/USDT:USDT")
    p = PortfolioContext(MagicMock(), 1, market_type="PERP", symbols=["BTC/USDT:USDT"])
    r = RunnerContext(MagicMock(), 1, exchange="OKX", market_type="PERP", symbol="BTC/USDT:USDT")
    for ctx in (b, p, r):
        with pytest.raises(ValueError, match="position_effect 非法"):
            ctx.place_order(symbol="BTC/USDT:USDT", order_type="MARKET", amount="1")
        with pytest.raises(ValueError, match="禁传 side"):
            ctx.place_order(symbol="BTC/USDT:USDT", side="BUY", order_type="MARKET", amount="1", position_effect="OPEN_LONG")
        with pytest.raises(TypeError, match="拒绝 float"):
            ctx.place_order(symbol="BTC/USDT:USDT", order_type="MARKET", amount=1.5, position_effect="OPEN_LONG")


def test_close_position_flat_no_position_differential():
    """无持仓 close_position:回测/runner 同返 NO_POSITION 回执(不下单不抛)。"""
    b = BacktestContext(MagicMock(), 1, symbol="BTC/USDT")
    r = RunnerContext(MagicMock(), 1, exchange="OKX", market_type="SPOT", symbol="BTC/USDT")
    r._client.trade.positions.return_value = []
    for ctx in (b, r):
        ack = ctx.close_position()
        assert isinstance(ack, OrderAck)
        assert ack.accepted is False
        assert ack.reason == "NO_POSITION"


def test_three_ctx_symbol_resolution_differential():
    """单标的/runner 绑定 symbol 可省且异 symbol 拒;组合必传(缺省即契约违规)。"""
    b, p, r = _all_ctxs()
    assert b.position().symbol == "BTC/USDT"
    # runner 走 mock REST(MagicMock 迭代为空)→ 零持仓
    assert r.position().qty == Decimal(0)
    for ctx in (b, r):
        with pytest.raises(ValueError, match="不接受其他 symbol"):
            ctx.position("ETH/USDT")
    with pytest.raises(ValueError, match="必须显式传 symbol"):
        p.position()


# ---------- Protocol 结构断言 ----------


def test_all_ctxs_satisfy_strategy_context_protocol():
    """runtime_checkable Protocol:方法/属性存在性断言(签名漂移的第一道拦截)。"""
    for ctx in _all_ctxs():
        assert isinstance(ctx, StrategyContext)


# ---------- 运行时能力分叉:预估资金费仅 runner(strategy-api §9,显式锁定不悄悄放宽) ----------


def test_predicted_funding_rate_runtime_divergence():
    """predicted_funding_rate 是三 ctx 都实现(Protocol 成员,isinstance 不破)但**行为分叉**的
    runner-only 能力:回测/组合抛 NotImplementedError(喂回测即 lookahead,strategy-api §9),
    runner 返 Decimal。分叉在此显式断言——新增能力"三处都动"、差分收紧而非放宽。"""
    for ctx in (
        BacktestContext(MagicMock(), 1, market_type="PERP", symbol="BTC/USDT:USDT"),
        PortfolioContext(MagicMock(), 1, market_type="PERP", symbols=["BTC/USDT:USDT"]),
    ):
        with pytest.raises(NotImplementedError, match="仅 runner"):
            ctx.predicted_funding_rate()

    r = RunnerContext(MagicMock(), 1, exchange="OKX", market_type="PERP", symbol="BTC/USDT:USDT")
    r._client.data.funding_rate.return_value = {"fundingRate": "0.0001", "markPrice": "63000"}
    assert r.predicted_funding_rate() == Decimal("0.0001")
    # 数据不可得 → None(绝不造值);SPOT runner → None 且不打请求
    r._client.data.funding_rate.return_value = None
    assert r.predicted_funding_rate() is None
    r_spot = RunnerContext(MagicMock(), 1, exchange="OKX", market_type="SPOT", symbol="BTC/USDT")
    assert r_spot.predicted_funding_rate() is None
    assert r_spot._client.data.funding_rate.call_count == 0


# ---------- dust 容差 ----------


def test_clamp_dust_close_within_tolerance():
    held = Decimal("0.3")
    want = Decimal("0.1") + Decimal("0.2") + Decimal("1e-15")  # 运算残差形态
    assert clamp_dust_close(held, want) == held


def test_clamp_dust_close_beyond_tolerance_rejected():
    held = Decimal("0.3")
    assert clamp_dust_close(held, held + DUST_TOLERANCE) is None  # 恰等容差 → 不 clamp
    assert clamp_dust_close(held, held + Decimal("0.001")) is None


def test_clamp_dust_close_edge_cases():
    assert clamp_dust_close(Decimal("0"), Decimal("1e-15")) is None  # 无持仓 → 正常拒
    assert clamp_dust_close(Decimal("0.3"), Decimal("0.3")) is None  # 足额 → 不触发
    assert clamp_dust_close(Decimal("0.3"), Decimal("0.2")) is None  # 未超 → 不触发
