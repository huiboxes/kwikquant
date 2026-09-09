"""PERP 数学内核差分对拍(Python 侧):与 JUnit PerpMathFixturesTest 跑同一批 fixtures。

fixtures 是内核语义单一真相源(docs/perp-math-spec.md §5)的机读形式;数值比较用 Decimal ==
(数值相等,忽略 scale 表示),错误用例经 ERROR_MAP 映射到 ValueError + 消息子串(spec §4,
Python 一律 ValueError,消息逐字镜像 Java)。新增错误码必须同步 Java runner 的 ERROR_MAP。
"""

from __future__ import annotations

import json
import re
from decimal import Decimal
from pathlib import Path

import pytest

from kwikquant_worker import perp_math
from kwikquant_worker.perp_math import PositionDelta

FIXTURES_DIR = Path(__file__).resolve().parents[1] / "fixtures" / "perp"

# spec §4 错误码 → 消息子串(Python 一律 ValueError)
ERROR_MAP = {
    "POSITIVE_REQUIRED": "must be positive",
    "NON_NEGATIVE_REQUIRED": "must be non-negative",
    "NON_TERMINATING_CONTRACTS": "Non-terminating decimal expansion",
    "OVER_CLOSE": "over-position",
    "INVALID_LEVERAGE": "leverage must be >= 1",
    "INVALID_RATE": "maintMarginRate must be in (0, 1)",
    "INVALID_SIDE": "positionSide must be LONG or SHORT",
}


def _load_fixtures():
    return sorted(FIXTURES_DIR.glob("*.json"))


def _dec(inp: dict, key: str) -> Decimal | None:
    v = inp.get(key)
    return None if v is None else Decimal(str(v))


def _invoke(function: str, i: dict):
    """分派表:fixture function → 内核调用。runner 只做解析/分派/比较,不含业务逻辑(spec §5)。"""
    if function == "toContracts":
        return perp_math.to_contracts(_dec(i, "coinAmount"), _dec(i, "contractSize"))
    if function == "toCoin":
        return perp_math.to_coin(_dec(i, "contracts"), _dec(i, "contractSize"))
    if function == "signedDelta":
        return perp_math.signed_delta(i["side"], _dec(i, "qty"))
    if function == "initialMargin":
        return perp_math.initial_margin(_dec(i, "price"), _dec(i, "qty"), i["leverage"])
    if function == "maintenanceMarginRequired":
        return perp_math.maintenance_margin_required(_dec(i, "markPrice"), _dec(i, "qty"), _dec(i, "maintMarginRate"))
    if function == "liquidationPriceIsolated":
        return perp_math.liquidation_price_isolated(
            _dec(i, "avgEntryPrice"), _dec(i, "qty"), _dec(i, "margin"), _dec(i, "maintMarginRate"), i.get("positionSide")
        )
    if function == "marginBreached":
        return perp_math.margin_breached(_dec(i, "marginBalance"), _dec(i, "maintMarginRequired"))
    if function == "fundingAmount":
        return perp_math.funding_amount(
            i.get("positionSide"), _dec(i, "fundingRate"), _dec(i, "markPrice"), _dec(i, "qty")
        )
    if function == "closedPnl":
        return perp_math.closed_pnl(
            i.get("positionSide"), _dec(i, "avgEntryPrice"), _dec(i, "exitPrice"), _dec(i, "closeQty")
        )
    if function == "weightedAvgEntryPrice":
        return perp_math.weighted_avg_entry_price(
            _dec(i, "oldAvgEntryPrice"), _dec(i, "oldQty"), _dec(i, "fillPrice"), _dec(i, "fillQty")
        )
    if function == "frozenMarginRelease":
        return perp_math.frozen_margin_release(
            _dec(i, "currentFrozenMargin"), _dec(i, "currentQty"), _dec(i, "closeQty")
        )
    if function == "applyPositionDelta":
        return perp_math.apply_position_delta(
            i.get("positionSide"),
            bool(i["open"]),
            _dec(i, "currentQty"),
            _dec(i, "currentAvgEntryPrice"),
            _dec(i, "currentFrozenMargin"),
            _dec(i, "fillQty"),
            _dec(i, "fillPrice"),
            i["leverage"],
        )
    # 未知 function 必须显式失败,防 typo fixture 静默跳过(spec §5)
    raise AssertionError(f"unknown function in fixture: {function}")


def test_fixtures_dir_not_empty():
    assert _load_fixtures(), "tests/fixtures/perp 目录为空"


@pytest.mark.parametrize("path", _load_fixtures(), ids=lambda p: p.stem)
def test_fixture(path: Path):
    doc = json.loads(path.read_text(encoding="utf-8"))
    function = doc["function"]
    inp = doc["input"]
    expected = doc["expected"]
    description = doc["description"]

    if "error" in expected:
        code = expected["error"]
        assert code in ERROR_MAP, f"{description}: 错误码 {code} 已注册(spec §4)"
        with pytest.raises(ValueError, match=re.escape(ERROR_MAP[code])):
            _invoke(function, inp)
        return

    actual = _invoke(function, inp)

    if isinstance(actual, PositionDelta):
        assert actual.new_qty == Decimal(expected["newQty"]), "newQty"
        if expected["newAvgEntryPrice"] is None:
            assert actual.new_avg_entry_price is None, "newAvgEntryPrice 应为 null"
        else:
            assert actual.new_avg_entry_price == Decimal(expected["newAvgEntryPrice"]), "newAvgEntryPrice"
        assert actual.realized_pnl_delta == Decimal(expected["realizedPnlDelta"]), "realizedPnlDelta"
        assert actual.margin_delta == Decimal(expected["marginDelta"]), "marginDelta"
    elif isinstance(actual, bool):
        assert actual is expected["value"], description
    else:
        assert actual == Decimal(expected["value"]), description
