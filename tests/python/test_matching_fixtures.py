"""撮合差分对拍(Python 侧):与 JUnit MatchingKernelFixturesTest 跑同一批 fixtures。

FAST fixtures → Python 回测引擎逐字段断言(price/qty/fee/feeCurrency/liquidity/是否成交);
Java-only fixtures(SPREAD/DEPTH fidelity、订单状态机语义)→ 断言引擎显式拒绝或按剩余量映射。
单一真相源:docs/matching-spec.md §8。
"""

from __future__ import annotations

import json
from decimal import Decimal
from pathlib import Path

import pytest

from kwikquant_worker.backtest.matching import LocalFill, MatchConfig, OrderIntent, match

FIXTURES_DIR = Path(__file__).resolve().parents[1] / "fixtures" / "matching"


def _load_fixtures():
    return sorted(FIXTURES_DIR.glob("*.json"))


def _intent(order: dict, filled: str | None = None) -> OrderIntent:
    """fixture order → OrderIntent。filledQty 映射为剩余量(Java remainingQty 语义)。"""
    amount = Decimal(order["amount"])
    if filled is not None:
        amount = amount - Decimal(filled)
    price = order.get("price")
    return OrderIntent(
        symbol=order.get("symbol", "BTC/USDT"),
        side=order["side"],
        order_type=order["orderType"],
        amount=amount,
        price=Decimal(price) if price is not None else None,
    )


def _config(raw: dict) -> MatchConfig:
    return MatchConfig(
        fidelity=raw.get("fidelity", "FAST"),
        market_slippage_bps=Decimal(raw.get("marketSlippageBps", "5")),
        partial_fill_enabled=bool(raw.get("partialFillEnabled", False)),
        maker_fee_rate=Decimal(raw.get("makerFeeRate", "0.001")),
        taker_fee_rate=Decimal(raw.get("takerFeeRate", "0.002")),
    )


def test_fixtures_dir_not_empty():
    assert _load_fixtures(), "tests/fixtures/matching 目录为空"


@pytest.mark.parametrize("path", _load_fixtures(), ids=lambda p: p.stem)
def test_fixture(path: Path):
    doc = json.loads(path.read_text(encoding="utf-8"))
    config = _config(doc["config"])
    order = doc["order"]
    snapshot = doc["snapshot"]
    expected = doc["expected"]

    if config.fidelity != "FAST":
        # SPREAD/DEPTH 是模拟盘语义(需 ticker/orderbook),回测引擎显式拒绝(spec §1)
        with pytest.raises(ValueError, match="FAST only"):
            match(_intent(order), snapshot, config)
        return
    if order.get("status") is not None:
        # Java 订单状态机语义(终态不撮合);Python 意图订单无状态机,该用例 Java-only
        return

    fill = match(_intent(order, order.get("filledQty")), snapshot, config)

    if expected is None:
        assert fill is None, f"{doc['description']} 应无成交,实际 {fill}"
        return
    assert isinstance(fill, LocalFill), f"{doc['description']} 应有成交"
    assert fill.price == Decimal(expected["price"]), "price"
    assert fill.qty == Decimal(expected["qty"]), "qty"
    assert fill.fee == Decimal(expected["fee"]), "fee"
    assert fill.fee_currency == expected["feeCurrency"], "feeCurrency"
    assert fill.liquidity == expected["liquidity"], "liquidity"
    # filled_at = 快照 timestamp(确定性,参与对拍)
    assert fill.filled_at == snapshot["timestamp"], "filled_at"
