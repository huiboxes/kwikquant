"""PERP 纯数学内核 — ``docs/perp-math-spec.md`` 的 Python 实现(镜像 Java ``shared/types/PerpMath``)。

语义单一真相源 = ``docs/perp-math-spec.md``;漂移由 ``tests/fixtures/perp`` 差分对拍拦截
(JUnit ``PerpMathFixturesTest`` 与 pytest ``test_perp_math_fixtures.py`` 跑同一批 fixtures,
CI 双门控)。修改任何函数的运算顺序/舍入点/校验必须先改规范与 fixtures,再同步双侧实现。

定点纪律(spec §2):全 ``Decimal``(拒绝 float);所有运算在 ``localcontext(prec=50,
ROUND_HALF_UP)`` 内,舍入点显式 ``quantize``;HALF_UP 两侧都是远离零。EXACT 类函数
(精确加减乘/整除)不额外舍入。校验失败一律 ``ValueError``,消息逐字镜像 Java
(便于跨语言 grep);错误码 → 消息子串映射见 spec §4。

单位口径:域内规范单位 = 币数量(base coin);张数只存在于交易所边界(to_contracts/to_coin
仅为对拍守护与 SDK 完备性,回测无交易所边界)。
"""

from __future__ import annotations

from dataclasses import dataclass
from decimal import ROUND_HALF_UP, Context, Decimal, localcontext
from math import gcd

# spec §2:Python 侧精度包络;生产量级下与 Java 无限精度 + setScale 逐位一致
CONTEXT_PREC = 50
_CTX = Context(prec=CONTEXT_PREC, rounding=ROUND_HALF_UP)

_SCALE_8 = Decimal("0.00000001")

#: 简化维持保证金率默认值(单源在 Java PerpMath,此处镜像;spec §2)
DEFAULT_MAINT_MARGIN_RATE = Decimal("0.005")

#: positionSide 规范值(与 Java PerpMath.SIDE_LONG/SIDE_SHORT 一致)
SIDE_LONG = "LONG"
SIDE_SHORT = "SHORT"

_SIDES = frozenset({"BUY", "SELL"})


# ---------- 校验(spec §4:消息逐字镜像 Java) ----------


def _require_positive(v: Decimal | None, name: str) -> Decimal:
    if v is None or v <= 0:
        raise ValueError(f"{name} must be positive, got: {v}")
    return v


def _require_non_negative(v: Decimal | None, name: str) -> Decimal:
    if v is None or v < 0:
        raise ValueError(f"{name} must be non-negative, got: {v}")
    return v


def _require_leverage(leverage: int) -> int:
    if leverage < 1:
        raise ValueError(f"leverage must be >= 1, got: {leverage}")
    return leverage


def _require_rate(maint_margin_rate: Decimal | None) -> Decimal:
    if maint_margin_rate is None or maint_margin_rate <= 0 or maint_margin_rate >= 1:
        raise ValueError(f"maintMarginRate must be in (0, 1), got: {maint_margin_rate}")
    return maint_margin_rate


def _require_position_side(position_side: str | None) -> bool:
    """校验 positionSide 并派生 short 标志(LONG→False / SHORT→True);其余值 fail-closed。"""
    if position_side == SIDE_LONG:
        return False
    if position_side == SIDE_SHORT:
        return True
    raise ValueError(f"positionSide must be LONG or SHORT, got: {position_side}")


def _unscaled(d: Decimal) -> tuple[int, int]:
    """Decimal → (带符号无标度整数, 指数):d = digits × 10^exp(不依赖 context 精度)。"""
    t = d.as_tuple()
    digits = 0
    for digit in t.digits:
        digits = digits * 10 + digit
    return (-digits if t.sign else digits), t.exponent


def _exact_divide(numerator: Decimal, denominator: Decimal) -> Decimal:
    """精确整除;除不尽抛 ValueError(镜像 Java BigDecimal.divide 的 ArithmeticException,fail-closed)。

    整数可除性判定(spec §3.1):a/b 约分后分母含 2/5 以外质因子即非终止;可除则整数运算
    构造精确商。**不做「prec 内除完回乘验证 q×b==a」**——商第 51 位向上舍入时 q×b 会在
    prec-50 里被舍回 a,约半数非终止输入漏报,fail-closed 变 fail-open。
    """
    na, ea = _unscaled(numerator)
    nb, eb = _unscaled(denominator)
    if nb == 0:
        raise ZeroDivisionError("division by zero")  # 防御:调用前已 requirePositive
    negative = (na < 0) != (nb < 0)
    na, nb = abs(na), abs(nb)
    g = gcd(na, nb)  # gcd(0, x) = x:na=0 约分后 nb=1,商 0
    na //= g
    nb //= g
    c2 = c5 = 0
    while nb % 2 == 0:
        nb //= 2
        c2 += 1
    while nb % 5 == 0:
        nb //= 5
        c5 += 1
    if nb != 1:
        raise ValueError("Non-terminating decimal expansion; no exact representable decimal result")
    k = max(c2, c5)
    q_int = na * (10**k // (2**c2 * 5**c5))
    return Decimal(-q_int if negative else q_int).scaleb(ea - eb - k)


# ---------- 张↔币换算(spec §3.1-§3.2,EXACT) ----------


def to_contracts(coin_amount: Decimal, contract_size: Decimal) -> Decimal:
    """币数量 → 张数(出站边界):精确整除,除不尽 fail-closed。"""
    with localcontext(_CTX):
        _require_positive(coin_amount, "coinAmount")
        _require_positive(contract_size, "contractSize")
        return _exact_divide(coin_amount, contract_size)


def to_coin(contracts: Decimal, contract_size: Decimal) -> Decimal:
    """张数 → 币数量(回流边界):乘法精确;允许 0 张(未成交单 fillSz="0"/平仓窗口 pos="0")。"""
    with localcontext(_CTX):
        _require_non_negative(contracts, "contracts")
        _require_positive(contract_size, "contractSize")
        return contracts * contract_size


# ---------- 净持仓增量(spec §3.3,EXACT) ----------


def signed_delta(side: str, qty: Decimal) -> Decimal:
    """方向化数量增量:BUY → +qty,SELL → −qty。"""
    with localcontext(_CTX):
        if side not in _SIDES:
            raise ValueError(f"side must be BUY or SELL, got: {side}")
        _require_positive(qty, "qty")
        return qty if side == "BUY" else -qty


# ---------- 保证金与强平(spec §3.4-§3.7) ----------


def initial_margin(price: Decimal, qty: Decimal, leverage: int) -> Decimal:
    """开仓初始保证金(SCALE_8):(price × qty) / leverage,除法 scale 8 HALF_UP。"""
    with localcontext(_CTX):
        _require_positive(price, "price")
        _require_positive(qty, "qty")
        _require_leverage(leverage)
        return (price * qty / Decimal(leverage)).quantize(_SCALE_8, rounding=ROUND_HALF_UP)


def maintenance_margin_required(mark_price: Decimal, qty: Decimal, maint_margin_rate: Decimal) -> Decimal:
    """单仓维持保证金(EXACT):markPrice × qty × mmr,不舍入(聚合后一次比较,避免 dust 累积)。"""
    with localcontext(_CTX):
        _require_positive(mark_price, "markPrice")
        _require_positive(qty, "qty")
        _require_rate(maint_margin_rate)
        return mark_price * qty * maint_margin_rate


def liquidation_price_isolated(
    avg_entry_price: Decimal, qty: Decimal, margin: Decimal, maint_margin_rate: Decimal, position_side: str
) -> Decimal:
    """逐仓简化强平价,margin-aware(SCALE_8)。运算顺序影响结果(spec §3.6):

    notional = avg × qty(精确);LONG: (notional − margin)/(qty × (1 − mmr)),
    SHORT: (notional + margin)/(qty × (1 + mmr));分子/分母精确,除法 quantize scale 8
    HALF_UP(分母如 0.995=199/200 含 2/5 以外质因子,普遍非终止)。

    margin 允许负(资金费侵蚀穿仓);结果可 ≤0(保证金耗尽)→ 触发判定用 margin_breached,
    本函数输出只作展示/参考价。
    """
    with localcontext(_CTX):
        _require_positive(avg_entry_price, "avgEntryPrice")
        _require_positive(qty, "qty")
        if margin is None:
            raise ValueError("margin must not be null")
        _require_rate(maint_margin_rate)
        short = _require_position_side(position_side)
        notional = avg_entry_price * qty
        numerator = notional + margin if short else notional - margin
        factor = Decimal(1) + maint_margin_rate if short else Decimal(1) - maint_margin_rate
        return (numerator / (qty * factor)).quantize(_SCALE_8, rounding=ROUND_HALF_UP)


def margin_breached(margin_balance: Decimal, maint_margin_required: Decimal) -> bool:
    """保证金穿仓触发谓词:marginBalance ≤ 0 或 maintMargin ≥ marginBalance(相等即触发)。

    CROSS 账户级与 ISOLATED 单仓共用;ISOLATED 判定必须用本谓词而非价格比较
    (资金费侵蚀后 LONG 强平价可 ≤0,价格比较永不触发)。
    """
    with localcontext(_CTX):
        if margin_balance is None:
            raise ValueError("marginBalance must not be null")
        _require_non_negative(maint_margin_required, "maintMarginRequired")
        return margin_balance <= 0 or maint_margin_required >= margin_balance


# ---------- 资金费与平仓盈亏(spec §3.8-§3.9) ----------


def funding_amount(position_side: str, funding_rate: Decimal, mark_price: Decimal, qty: Decimal) -> Decimal:
    """单期资金费结算金额,持仓视角带符号(SCALE_8):正=收,负=付。

    sideSign = SHORT ? +1 : −1;正费率多头付空头收(OKX 语义),负费率反转。
    funding_rate 可为负、可为 0,不校验范围;positionSide 非 LONG/SHORT fail-closed(不猜方向)。
    """
    with localcontext(_CTX):
        short = _require_position_side(position_side)
        if funding_rate is None:
            raise ValueError("fundingRate must not be null")
        _require_positive(mark_price, "markPrice")
        _require_positive(qty, "qty")
        side_sign = Decimal(1) if short else Decimal(-1)
        return (funding_rate * mark_price * qty * side_sign).quantize(_SCALE_8, rounding=ROUND_HALF_UP)


def closed_pnl(position_side: str, avg_entry_price: Decimal, exit_price: Decimal, close_qty: Decimal) -> Decimal:
    """平仓已实现盈亏(毛额,EXACT):LONG (exit−avg)×qty / SHORT (avg−exit)×qty,可为负。"""
    with localcontext(_CTX):
        short = _require_position_side(position_side)
        _require_positive(avg_entry_price, "avgEntryPrice")
        _require_positive(exit_price, "exitPrice")
        _require_positive(close_qty, "closeQty")
        diff = avg_entry_price - exit_price if short else exit_price - avg_entry_price
        return diff * close_qty


# ---------- 持仓增量构件(spec §3.10-§3.11) ----------


def weighted_avg_entry_price(
    old_avg_entry_price: Decimal, old_qty: Decimal, fill_price: Decimal, fill_qty: Decimal
) -> Decimal:
    """加仓后的加权平均开仓价(SCALE_8)。建仓(oldQty=0)不走本函数,直接用 fillPrice。"""
    with localcontext(_CTX):
        _require_positive(old_avg_entry_price, "oldAvgEntryPrice")
        _require_positive(old_qty, "oldQty")
        _require_positive(fill_price, "fillPrice")
        _require_positive(fill_qty, "fillQty")
        total_cost = old_avg_entry_price * old_qty + fill_price * fill_qty
        return (total_cost / (old_qty + fill_qty)).quantize(_SCALE_8, rounding=ROUND_HALF_UP)


def frozen_margin_release(current_frozen_margin: Decimal, current_qty: Decimal, close_qty: Decimal) -> Decimal:
    """平仓释放的冻结保证金:全平精确释放(EXACT,免除法 dust);部分平按比例(SCALE_8)。"""
    with localcontext(_CTX):
        _require_non_negative(current_frozen_margin, "currentFrozenMargin")
        _require_positive(current_qty, "currentQty")
        _require_positive(close_qty, "closeQty")
        if close_qty > current_qty:
            raise ValueError(f"PERP CLOSE over-position: closeQty={close_qty} > qty={current_qty}")
        if close_qty == current_qty:
            return current_frozen_margin
        return (current_frozen_margin * close_qty / current_qty).quantize(_SCALE_8, rounding=ROUND_HALF_UP)


# ---------- 组合:三段语义(spec §3.12) ----------


@dataclass(frozen=True)
class PositionDelta:
    """apply_position_delta 输出契约(镜像 Java PerpMath.PositionDelta)。

    new_avg_entry_price 全平时为 None;margin_delta 正=冻结增加(开仓),负=释放(平仓);
    调用方持久化 new_frozen = current_frozen_margin + margin_delta。
    """

    new_qty: Decimal
    new_avg_entry_price: Decimal | None
    realized_pnl_delta: Decimal
    margin_delta: Decimal


def apply_position_delta(
    position_side: str,
    is_open: bool,
    current_qty: Decimal,
    current_avg_entry_price: Decimal | None,
    current_frozen_margin: Decimal,
    fill_qty: Decimal,
    fill_price: Decimal,
    leverage: int,
) -> PositionDelta:
    """单向桶(LONG/SHORT)内叠加一笔成交:建仓/加仓、部分减仓、全平三段语义,无反手。

    字段簿记与四向 positionEffect → (positionSide, open) 映射属于调用方;
    反向意图由调用方拆成 CLOSE + OPEN 两笔(净持仓模式的反手由回测账本基于
    signed_delta 自行分解)。
    """
    with localcontext(_CTX):
        _require_position_side(position_side)
        _require_non_negative(current_qty, "currentQty")
        _require_non_negative(current_frozen_margin, "currentFrozenMargin")
        _require_positive(fill_qty, "fillQty")
        _require_positive(fill_price, "fillPrice")
        _require_leverage(leverage)
        if is_open:
            new_qty = current_qty + fill_qty
            if current_qty == 0:
                new_avg = fill_price
            else:
                new_avg = weighted_avg_entry_price(
                    current_avg_entry_price, current_qty, fill_price, fill_qty
                )
            return PositionDelta(
                new_qty=new_qty,
                new_avg_entry_price=new_avg,
                realized_pnl_delta=Decimal(0),
                margin_delta=initial_margin(fill_price, fill_qty, leverage),
            )
        if fill_qty > current_qty:
            raise ValueError(f"PERP CLOSE over-position: fillQty={fill_qty} > qty={current_qty}")
        realized_pnl_delta = closed_pnl(position_side, current_avg_entry_price, fill_price, fill_qty)
        new_qty = current_qty - fill_qty
        release = frozen_margin_release(current_frozen_margin, current_qty, fill_qty)
        return PositionDelta(
            new_qty=new_qty,
            new_avg_entry_price=None if new_qty == 0 else current_avg_entry_price,
            realized_pnl_delta=realized_pnl_delta,
            margin_delta=-release,
        )
