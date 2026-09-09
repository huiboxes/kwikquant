"""订单接受性纯函数层 — ``docs/matching-spec.md`` §9 的 Python 实现(镜像 Java ``shared/types/OrderAcceptance``)。

与撮合并列的独立层:判定一笔订单在给定 :class:`PairSpec` 下是否可接受。消费方:回测
``place_order`` / 撮合前闸门(拒单进 warnings,不再静默)。语义漂移由
``tests/fixtures/matching/acceptance_*.json``(kind="acceptance")差分对拍拦截
(JUnit ``MatchingKernelFixturesTest`` 与 pytest ``test_matching_fixtures.py`` 跑同一批,CI 双门控)。
修改规则必须先改 spec §9 → 再改 fixtures → 再改双侧实现。

金额红线:全 ``Decimal``;规格字段接受 ``Decimal/str/int``(**不接受 float**,保精度)。
message 逐字镜像 Java(输入字面量原样字符串化,无算术派生值;null → "null" 而非 Python 的 "None")。
"""

from __future__ import annotations

from dataclasses import dataclass
from decimal import Decimal

#: PERP 杠杆全局保守上限(单一真相源 spec §9.2 规则 12,Java OrderAcceptance.MAX_LEVERAGE_CAP 镜像)
MAX_LEVERAGE_CAP = 100

_POSITION_EFFECTS = frozenset({"OPEN_LONG", "OPEN_SHORT", "CLOSE_LONG", "CLOSE_SHORT"})

#: positionEffect → side 派生表(Java PositionEffect.toSide 镜像,spec §9.2 规则 15)。
#: 公开常量:回测 ctx/账本消费同一单源(strategy.place_order 派生 side 用)。
EFFECT_TO_SIDE = {
    "OPEN_LONG": "BUY",
    "OPEN_SHORT": "SELL",
    "CLOSE_LONG": "SELL",
    "CLOSE_SHORT": "BUY",
}

_NEEDS_PRICE_TYPES = frozenset({"LIMIT", "STOP_LIMIT", "TAKE_PROFIT_LIMIT"})
_NEEDS_STOP_PRICE_TYPES = frozenset({"STOP_MARKET", "STOP_LIMIT", "TAKE_PROFIT_MARKET", "TAKE_PROFIT_LIMIT"})


def _dec(v: Decimal | str | int | None) -> Decimal | None:
    """金额字段 Decimal 化(None 透传;拒绝 float 防精度丢失,纪律同 matching._dec)。"""
    if v is None:
        return None
    if isinstance(v, Decimal):
        return v
    if isinstance(v, float):
        raise TypeError(f"acceptance rejects float amounts: {v!r}")
    return Decimal(str(v))


def _s(v: object) -> str:
    """message 拼接的字符串化:null → "null"(镜像 Java 字符串拼接,Python 默认会是 "None")。"""
    return "null" if v is None else str(v)


@dataclass(frozen=True)
class PairSpec:
    """交易对规格快照(镜像 Java ``shared/types/PairSpec``;全部币单位,见 spec §9.1)。"""

    symbol: str | None
    market_type: str | None
    min_qty: Decimal | None
    max_qty: Decimal | None
    tick_size: Decimal | None
    step_size: Decimal | None
    max_leverage: int | None

    @classmethod
    def from_dict(cls, raw: dict | None) -> "PairSpec | None":
        """Java Gateway 下发的 JSON 快照(camelCase 键,金额字符串)→ PairSpec;raw 为 None 返 None。"""
        if raw is None:
            return None
        max_lev = raw.get("maxLeverage")
        return cls(
            symbol=raw.get("symbol"),
            market_type=raw.get("marketType"),
            min_qty=_dec(raw.get("minQty")),
            max_qty=_dec(raw.get("maxQty")),
            tick_size=_dec(raw.get("tickSize")),
            step_size=_dec(raw.get("stepSize")),
            max_leverage=None if max_lev is None else int(max_lev),
        )


@dataclass(frozen=True)
class AcceptInput:
    """接受性判定输入(镜像 Java ``OrderAcceptance.Input``;不含墙钟字段,spec §9.1)。

    PERP 的 side 可 None(单源 position_effect 派生);SPOT 必填且合约字段必须全 None。
    """

    symbol: str | None
    market_type: str | None
    side: str | None
    order_type: str | None
    amount: Decimal | None
    price: Decimal | None = None
    stop_price: Decimal | None = None
    leverage: int | None = None
    margin_mode: str | None = None
    position_effect: str | None = None


@dataclass(frozen=True)
class AcceptResult:
    """判定输出(镜像 Java ``OrderAcceptance.AcceptResult``)。接受时 ok=True 且余字段 None。"""

    ok: bool
    reason_code: str | None = None
    message: str | None = None


_ACCEPTED = AcceptResult(True)


def _rejected(reason_code: str, message: str) -> AcceptResult:
    return AcceptResult(False, reason_code, message)


def check(inp: AcceptInput, pair_spec: PairSpec | None) -> AcceptResult:
    """规则表顺序敏感,命中即终止(spec §9.2 规则 1-19)。

    ``pair_spec is None`` = 交易对未知,fail-closed 拒(规则 4)。market_type 非 "PERP"(含 None)
    走 SPOT 分支——与 Java 侧及重构前 Order.validate 行为一致。
    """
    if inp.position_effect is not None and inp.position_effect not in _POSITION_EFFECTS:
        # Java 侧由枚举类型系统保证;Python 字符串输入 fail-closed 防御
        raise ValueError(f"unsupported position effect: {inp.position_effect!r}")
    if not inp.symbol or not str(inp.symbol).strip():
        return _rejected("SYMBOL_BLANK", "symbol is blank")
    if inp.order_type is None:
        return _rejected("ORDER_TYPE_REQUIRED", "orderType is required")
    if inp.amount is None or inp.amount <= 0:
        return _rejected("AMOUNT_POSITIVE", "amount must be positive")
    if pair_spec is None:
        return _rejected("UNKNOWN_SYMBOL", f"unknown symbol: {inp.symbol}")
    if pair_spec.min_qty is not None and inp.amount < pair_spec.min_qty:
        return _rejected("MIN_QTY", f"amount {_s(inp.amount)} < minQty {_s(pair_spec.min_qty)}")
    if pair_spec.max_qty is not None and inp.amount > pair_spec.max_qty:
        return _rejected("MAX_QTY", f"amount {_s(inp.amount)} > maxQty {_s(pair_spec.max_qty)}")
    # 对齐即保证 PERP 出站张数 sz = amount/contractSize 是 lotSz 整数倍(stepSize 已币化)
    if pair_spec.step_size is not None and pair_spec.step_size > 0:
        if inp.amount % pair_spec.step_size != 0:
            return _rejected(
                "STEP_SIZE",
                f"amount {_s(inp.amount)} not aligned to stepSize {_s(pair_spec.step_size)}",
            )
    if inp.order_type in _NEEDS_PRICE_TYPES and (inp.price is None or inp.price <= 0):
        return _rejected("PRICE_REQUIRED", f"price required for {inp.order_type}")
    if inp.order_type in _NEEDS_STOP_PRICE_TYPES and (inp.stop_price is None or inp.stop_price <= 0):
        return _rejected("STOP_PRICE_REQUIRED", f"stopPrice required for {inp.order_type}")
    if inp.price is not None and pair_spec.tick_size is not None and pair_spec.tick_size > 0:
        if inp.price % pair_spec.tick_size != 0:
            return _rejected(
                "TICK_SIZE",
                f"price {_s(inp.price)} not aligned to tickSize {_s(pair_spec.tick_size)}",
            )
    if inp.stop_price is not None and pair_spec.tick_size is not None and pair_spec.tick_size > 0:
        if inp.stop_price % pair_spec.tick_size != 0:
            return _rejected(
                "TICK_SIZE",
                f"stopPrice {_s(inp.stop_price)} not aligned to tickSize {_s(pair_spec.tick_size)}",
            )
    if inp.market_type == "PERP":
        return _check_perp(inp, pair_spec)
    if inp.side is None:
        return _rejected("SIDE_REQUIRED", "side is required")
    if inp.leverage is not None or inp.margin_mode is not None or inp.position_effect is not None:
        return _rejected("SPOT_CONTRACT_FIELDS", "SPOT order must not set leverage/marginMode/positionEffect")
    return _ACCEPTED


def _check_perp(inp: AcceptInput, pair_spec: PairSpec) -> AcceptResult:
    if inp.leverage is None or inp.leverage < 1 or inp.leverage > MAX_LEVERAGE_CAP:
        return _rejected(
            "LEVERAGE_RANGE",
            f"PERP leverage must be 1-{MAX_LEVERAGE_CAP}, got: {_s(inp.leverage)}",
        )
    if inp.margin_mode is None:
        return _rejected("MARGIN_MODE_REQUIRED", "PERP marginMode required (ISOLATED/CROSS)")
    if inp.position_effect is None:
        return _rejected(
            "POSITION_EFFECT_REQUIRED",
            "PERP positionEffect required (OPEN_LONG/OPEN_SHORT/CLOSE_LONG/CLOSE_SHORT)",
        )
    # 四象限一致性:side 单源=positionEffect 派生;显式传入的 side 与派生值矛盾即拒
    derived_side = EFFECT_TO_SIDE[inp.position_effect]
    if inp.side is not None and inp.side != derived_side:
        return _rejected(
            "SIDE_EFFECT_MISMATCH",
            f"side {inp.side} contradicts positionEffect {inp.position_effect} (expected side {derived_side})",
        )
    # per-symbol maxLeverage fail-closed:交易所未声明上限即拒单,不用兜底值放行
    if pair_spec.max_leverage is None:
        return _rejected(
            "MAX_LEVERAGE_UNDECLARED",
            f"PERP maxLeverage not declared for {inp.symbol}, refusing (fail-closed)",
        )
    if inp.leverage > pair_spec.max_leverage:
        return _rejected(
            "MAX_LEVERAGE",
            f"leverage {inp.leverage} exceeds maxLeverage {pair_spec.max_leverage} for {inp.symbol}",
        )
    return _ACCEPTED
