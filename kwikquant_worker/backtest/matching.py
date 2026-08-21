"""回测本地撮合引擎 — ``docs/matching-spec.md`` FAST fidelity 的 Python 实现。

语义单一真相源 = ``docs/matching-spec.md``(提取自 Java ``MatchingKernel``);漂移由
``tests/fixtures/matching`` 差分对拍拦截(JUnit ``MatchingKernelFixturesTest`` 与
pytest ``test_matching_fixtures.py`` 跑同一批 fixtures,CI 双门控)。

**适用范围**:回测只有 K 线数据 → 永远 FAST;SPREAD/DEPTH 需要 ticker bid/ask 与 L2
orderbook,仅模拟盘(Java MatchingKernel)使用,本引擎收到非 FAST 配置抛 ``ValueError``。

金额红线:全 ``Decimal``;快照金额字段接受 ``Decimal/str/int``(**不接受 float**,保精度)。
"""

from __future__ import annotations

from dataclasses import dataclass
from decimal import ROUND_HALF_UP, Decimal

BPS_DIVISOR = Decimal(10_000)
_SCALE_8 = Decimal("0.00000001")

_ORDER_TYPES = frozenset(
    {"MARKET", "LIMIT", "STOP_MARKET", "STOP_LIMIT", "TAKE_PROFIT_MARKET", "TAKE_PROFIT_LIMIT", "TRAILING_STOP"}
)
_CONDITIONAL_TYPES = frozenset(
    {"STOP_MARKET", "STOP_LIMIT", "TAKE_PROFIT_MARKET", "TAKE_PROFIT_LIMIT", "TRAILING_STOP"}
)


def _dec(v: Decimal | str | int | None) -> Decimal | None:
    """金额字段 Decimal 化(None 透传;拒绝 float 防精度丢失)。"""
    if v is None:
        return None
    if isinstance(v, Decimal):
        return v
    if isinstance(v, float):
        raise TypeError(f"matching engine rejects float amounts: {v!r}")
    return Decimal(str(v))


@dataclass(frozen=True)
class MatchConfig:
    """撮合配置(镜像 Java ``MatchConfig``;默认值两侧一致,仲裁依据 docs/matching-spec.md §2)。"""

    fidelity: str
    market_slippage_bps: Decimal
    partial_fill_enabled: bool
    maker_fee_rate: Decimal
    taker_fee_rate: Decimal

    @classmethod
    def defaults(cls) -> MatchConfig:
        return cls("FAST", Decimal("5"), False, Decimal("0.001"), Decimal("0.002"))

    @classmethod
    def from_dict(cls, raw: dict | None) -> MatchConfig:
        """从回测任务配置(JSON camelCase 键,Java Gateway 下发)构造;缺失键用默认值。"""
        d = cls.defaults()
        if not raw:
            return d

        def _dec_or(key: str, default: Decimal) -> Decimal:
            v = _dec(raw.get(key))
            return default if v is None else v  # 显式 None 判定:Decimal(0)(零滑点/零费率)是合法配置

        return cls(
            fidelity=str(raw.get("fidelity", d.fidelity)),
            market_slippage_bps=_dec_or("marketSlippageBps", d.market_slippage_bps),
            partial_fill_enabled=bool(raw.get("partialFillEnabled", d.partial_fill_enabled)),
            maker_fee_rate=_dec_or("makerFeeRate", d.maker_fee_rate),
            taker_fee_rate=_dec_or("takerFeeRate", d.taker_fee_rate),
        )


@dataclass(frozen=True)
class OrderIntent:
    """回测下单意图(event_loop NEXT_BAR 队列元素)。回测无订单状态机,无 id/status。"""

    symbol: str
    side: str  # BUY / SELL
    order_type: str  # MARKET / LIMIT / 条件单类型(不撮合)
    amount: Decimal
    price: Decimal | None


@dataclass(frozen=True)
class LocalFill:
    """本地撮合成交(镜像 Java ``Fill`` 的对拍字段;无 externalFillId——随机不参与对拍)。"""

    price: Decimal
    qty: Decimal
    fee: Decimal
    fee_currency: str | None
    liquidity: str  # taker / maker
    filled_at: str


def match(intent: OrderIntent, snapshot: dict, config: MatchConfig) -> LocalFill | None:
    """按 spec 撮合一笔意图。返 ``LocalFill`` 或 ``None``(未触发/不可撮合)。

    与 Java ``MatchingKernel.match`` 逐条对应(spec §3-§6):门槛 → 类型分派 → 成交价 → 费用。
    """
    if intent.side not in ("BUY", "SELL"):
        raise ValueError(f"unsupported order side: {intent.side!r}")
    if intent.order_type not in _ORDER_TYPES:
        raise ValueError(f"unsupported order type: {intent.order_type!r}")
    if config.fidelity != "FAST":
        # 回测只有 K 线 → FAST;SPREAD/DEPTH 是模拟盘语义(spec §1)
        raise ValueError(f"backtest matching engine supports FAST only, got {config.fidelity!r}")
    # 门槛:剩余数量 ≤ 0 / 条件单不主动触发(spec §3)
    if intent.amount <= 0:
        return None
    if intent.order_type in _CONDITIONAL_TYPES:
        return None
    if intent.order_type == "MARKET":
        return _match_market(intent, snapshot, config)
    return _match_limit(intent, snapshot, config)


def _match_market(intent: OrderIntent, snapshot: dict, config: MatchConfig) -> LocalFill | None:
    """FAST 市价单:last × (1 ± 滑点因子);滑点因子先 8 位 HALF_UP 再乘(spec §4)。"""
    last = _dec(snapshot.get("last"))
    if last is None:
        return None
    sign = 1 if intent.side == "BUY" else -1
    factor = (config.market_slippage_bps / BPS_DIVISOR).quantize(_SCALE_8, rounding=ROUND_HALF_UP) * sign
    raw_price = last * (Decimal(1) + factor)
    if raw_price <= 0:
        return None
    return _build_fill(intent, raw_price, config.taker_fee_rate, "taker", snapshot)


def _match_limit(intent: OrderIntent, snapshot: dict, config: MatchConfig) -> LocalFill | None:
    """FAST 限价单:BUY low ≤ price / SELL high ≥ price 触发,按限价 maker 成交(spec §5)。"""
    if intent.price is None:
        return None
    low = _dec(snapshot.get("low"))
    high = _dec(snapshot.get("high"))
    if low is None or high is None:
        return None
    triggered = low <= intent.price if intent.side == "BUY" else high >= intent.price
    if not triggered:
        return None
    return _build_fill(intent, intent.price, config.maker_fee_rate, "maker", snapshot)


def _build_fill(
    intent: OrderIntent, raw_price: Decimal, fee_rate: Decimal, liquidity: str, snapshot: dict
) -> LocalFill:
    """构造成交:fee 用未舍入成交价算,fee/price 各自 8 位 HALF_UP(spec §6)。"""
    fee = (raw_price * intent.amount * fee_rate).quantize(_SCALE_8, rounding=ROUND_HALF_UP)
    return LocalFill(
        price=raw_price.quantize(_SCALE_8, rounding=ROUND_HALF_UP),
        qty=intent.amount,
        fee=fee,
        fee_currency=_infer_fee_currency(intent.symbol),
        liquidity=liquidity,
        filled_at=str(snapshot.get("timestamp", "")),
    )


def _infer_fee_currency(symbol: str) -> str | None:
    """feeCurrency = symbol 的 quote 部分(与 Java inferFeeCurrency 同规则)。"""
    if not symbol:
        return None
    slash = symbol.find("/")
    if slash <= 0 or slash >= len(symbol) - 1:
        return None
    return symbol[slash + 1 :]
