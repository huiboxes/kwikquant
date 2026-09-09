"""acceptance.py 直接单测(fixtures 对拍之外的实现细节:from_dict/float 拒绝/枚举防御)。

规则语义与 reasonCode/message 逐字对拍由 tests/fixtures/matching/acceptance_*.json
(test_matching_fixtures.py,CI 双门控)锁定,本文件不重复。
"""

from __future__ import annotations

from decimal import Decimal

import pytest

from kwikquant_worker.acceptance import AcceptInput, PairSpec, check


def _perp_spec(**over) -> PairSpec:
    base = {
        "symbol": "BTC/USDT",
        "market_type": "PERP",
        "min_qty": Decimal("0.001"),
        "max_qty": None,
        "tick_size": Decimal("0.1"),
        "step_size": Decimal("0.001"),
        "max_leverage": 100,
    }
    base.update(over)
    return PairSpec(**base)


def _perp_input(**over) -> AcceptInput:
    base = {
        "symbol": "BTC/USDT",
        "market_type": "PERP",
        "side": "BUY",
        "order_type": "MARKET",
        "amount": Decimal("0.01"),
        "leverage": 10,
        "margin_mode": "ISOLATED",
        "position_effect": "OPEN_LONG",
    }
    base.update(over)
    return AcceptInput(**base)


class TestFromDict:
    def test_none_returns_none(self):
        assert PairSpec.from_dict(None) is None

    def test_camel_case_json_strings(self):
        spec = PairSpec.from_dict(
            {
                "symbol": "BTC/USDT",
                "marketType": "PERP",
                "minQty": "0.001",
                "maxQty": "10",
                "tickSize": "0.1",
                "stepSize": "0.001",
                "maxLeverage": 100,
            }
        )
        assert spec.min_qty == Decimal("0.001")
        assert spec.max_qty == Decimal("10")
        assert spec.max_leverage == 100

    def test_null_fields_stay_none(self):
        spec = PairSpec.from_dict({"symbol": "BTC/USDT", "marketType": "SPOT"})
        assert spec.min_qty is None and spec.max_leverage is None

    def test_float_amount_rejected(self):
        with pytest.raises(TypeError, match="rejects float"):
            PairSpec.from_dict({"symbol": "BTC/USDT", "marketType": "PERP", "minQty": 0.001})


class TestCheckDefenses:
    def test_invalid_position_effect_raises(self):
        # Java 侧由枚举类型系统保证;Python 字符串输入 fail-closed
        with pytest.raises(ValueError, match="unsupported position effect"):
            check(_perp_input(position_effect="OPEN_UP"), _perp_spec())

    def test_blank_symbol_variants(self):
        for blank in ("", "   ", None):
            result = check(_perp_input(symbol=blank), _perp_spec())
            assert not result.ok and result.reason_code == "SYMBOL_BLANK"

    def test_accepted_result_fields(self):
        result = check(_perp_input(), _perp_spec())
        assert result.ok and result.reason_code is None and result.message is None

    def test_leverage_null_message_says_null_not_none(self):
        # message 逐字镜像 Java:"got: null"(Python 默认 str(None)="None" 是漂移)
        result = check(_perp_input(leverage=None), _perp_spec())
        assert result.message == "PERP leverage must be 1-100, got: null"

    def test_market_type_none_goes_spot_branch(self):
        # 与 Java 及重构前 Order.validate 一致:非 PERP(含 None)走 SPOT 分支
        result = check(
            AcceptInput(
                symbol="BTC/USDT", market_type=None, side="BUY", order_type="MARKET", amount=Decimal("0.1")
            ),
            PairSpec("BTC/USDT", None, None, None, None, None, None),
        )
        assert result.ok

    def test_step_size_zero_skips_alignment(self):
        # stepSize ≤ 0 = 交易所未声明有效步长,跳过对齐校验(镜像 Java signum() > 0 守卫)
        spec = _perp_spec(step_size=Decimal("0"))
        assert check(_perp_input(amount=Decimal("0.0015")), spec).ok
