"""回测本地撮合引擎单元测试(语义差分由 test_matching_fixtures.py 覆盖,此处测配置/边界/守卫)。"""

from __future__ import annotations

from decimal import Decimal

import pytest

from kwikquant_worker.backtest.matching import MatchConfig, OrderIntent, match


def _snap(**kw) -> dict:
    base = {"timestamp": "2026-06-30T00:00:00Z", "last": "42000", "open": "42000",
            "high": "42100", "low": "41900", "close": "42000"}
    base.update(kw)
    return base


def _intent(side="BUY", order_type="MARKET", amount="0.1", price=None, symbol="BTC/USDT"):
    return OrderIntent(symbol=symbol, side=side, order_type=order_type,
                       amount=Decimal(amount), price=Decimal(price) if price is not None else None)


# ---------- MatchConfig ----------


def test_defaults_match_java_match_config():
    """默认值 = Java MatchConfig.defaults()(spec §2,两侧一致的仲裁依据)。"""
    c = MatchConfig.defaults()
    assert c.fidelity == "FAST"
    assert c.market_slippage_bps == Decimal("5")
    assert c.partial_fill_enabled is False
    assert c.maker_fee_rate == Decimal("0.001")
    assert c.taker_fee_rate == Decimal("0.002")


def test_from_dict_none_and_empty_return_defaults():
    assert MatchConfig.from_dict(None) == MatchConfig.defaults()
    assert MatchConfig.from_dict({}) == MatchConfig.defaults()


def test_from_dict_parses_camel_case_keys():
    c = MatchConfig.from_dict({
        "fidelity": "FAST", "marketSlippageBps": "10", "partialFillEnabled": False,
        "makerFeeRate": "0.0008", "takerFeeRate": "0.001",
    })
    assert c.market_slippage_bps == Decimal("10")
    assert c.maker_fee_rate == Decimal("0.0008")
    assert c.taker_fee_rate == Decimal("0.001")


def test_from_dict_zero_values_are_not_replaced_by_defaults():
    """零滑点/零费率是合法配置,不能被 or 短路成默认值。"""
    c = MatchConfig.from_dict({"marketSlippageBps": "0", "makerFeeRate": "0", "takerFeeRate": "0"})
    assert c.market_slippage_bps == Decimal("0")
    assert c.maker_fee_rate == Decimal("0")
    assert c.taker_fee_rate == Decimal("0")


def test_from_dict_partial_uses_defaults_for_missing():
    c = MatchConfig.from_dict({"marketSlippageBps": "7"})
    assert c.market_slippage_bps == Decimal("7")
    assert c.maker_fee_rate == Decimal("0.001")  # 缺失键默认


# ---------- 适用范围守卫 ----------


def test_non_fast_fidelity_raises():
    """SPREAD/DEPTH 是模拟盘语义,回测引擎显式拒绝(spec §1)。"""
    for fidelity in ("SPREAD", "DEPTH"):
        with pytest.raises(ValueError, match="FAST only"):
            match(_intent(), _snap(), MatchConfig(fidelity, Decimal("5"), False, Decimal("0.001"), Decimal("0.002")))


def test_unknown_side_raises():
    with pytest.raises(ValueError, match="side"):
        match(OrderIntent("BTC/USDT", "BUYBACK", "MARKET", Decimal("0.1"), None), _snap(), MatchConfig.defaults())


def test_unknown_order_type_raises():
    with pytest.raises(ValueError, match="order type"):
        match(OrderIntent("BTC/USDT", "BUY", "FOO", Decimal("0.1"), None), _snap(), MatchConfig.defaults())


def test_float_amount_rejected():
    """金额红线:快照金额字段拒 float。"""
    from kwikquant_worker.backtest.matching import _dec

    with pytest.raises(TypeError):
        _dec(42000.5)


# ---------- 行为边界 ----------


def test_zero_slippage_fills_at_last():
    cfg = MatchConfig.from_dict({"marketSlippageBps": "0"})
    fill = match(_intent(), _snap(), cfg)
    assert fill is not None and fill.price == Decimal("42000.00000000")


def test_zero_fee_rates_produce_zero_fee():
    cfg = MatchConfig.from_dict({"makerFeeRate": "0", "takerFeeRate": "0"})
    fill = match(_intent(), _snap(), cfg)
    assert fill is not None and fill.fee == Decimal("0E-8")


def test_market_negative_amount_no_fill():
    assert match(_intent(amount="-1"), _snap(), MatchConfig.defaults()) is None


def test_snapshot_decimal_values_accepted():
    """快照金额接受 Decimal/str/int(不接受 float)。"""
    snap = _snap(last=Decimal("42000"))
    assert match(_intent(), snap, MatchConfig.defaults()) is not None


def test_fee_currency_symbol_without_slash_is_none():
    fill = match(_intent(symbol="BTCUSDT"), _snap(), MatchConfig.defaults())
    assert fill is not None and fill.fee_currency is None
