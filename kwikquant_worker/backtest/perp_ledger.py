"""PERP 回测账本 — ``docs/perp-backtest-spec.md`` §3-§5 的实现。

净持仓模式(signed_qty,正 LONG 负 SHORT;≠ 模拟盘 positionSide 分行,spec §1 声明的有意差异);
钱数学**全部**委托 ``perp_math`` 内核(与 Java PerpMath 差分对拍,perp-math-spec.md)——本模块
只做编排:四向意图分解(spec §3.3)、bar 极值强平近似(spec §4)、资金费期次回放(spec §5)。

金额红线:全 ``Decimal``;输出序列化前过 :func:`norm`(负零规范化,spec §8——全平时内核
margin_delta 可为 ``Decimal("-0")``,严禁 ``"-0"`` 进 JSON)。
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from decimal import ROUND_HALF_UP, Decimal

from kwikquant_worker import perp_math

_SCALE_8 = Decimal("0.00000001")

#: 四向意图 → signed delta 方向(spec §3.2)
_EFFECT_DELTA_SIGN = {
    "OPEN_LONG": 1,
    "CLOSE_SHORT": 1,
    "OPEN_SHORT": -1,
    "CLOSE_LONG": -1,
}

#: timeframe(ccxt 记法)→ 秒。bar 资金费归属边界用(spec §5.1),与 funding 期次 interval 相互独立。
_TF_UNIT_SECONDS = {"m": 60, "h": 3600, "d": 86400, "w": 604800}

# bar 资金费归属窗不得超过 Java 端点 24h 前瞻缓冲(见 timeframe_seconds docstring)
_MAX_TIMEFRAME_SECONDS = 86400


def norm(d: Decimal) -> Decimal:
    """负零规范化:``Decimal("-0") == 0`` 但 ``str`` 输出 "-0"(spec §8 序列化纪律)。"""
    return Decimal(0) if d == 0 else d


def timeframe_seconds(timeframe: str) -> int:
    """``"15m"/"4h"/"1d"`` → 秒数;非法记法抛 ValueError(fail-closed,不猜周期)。

    大小写敏感:ccxt 月线记法 "1M" 与分钟线 "1m" 只差大小写,折叠会把月线静默误读成分钟线。
    上限 1d(86400s):bar 的资金费归属窗 (ts, ts+周期] 必须落在 Java funding-rates 端点
    24h 前瞻缓冲(FundingCoverageGuard.fundingQueryEnd)之内,更长周期的归属窗超出缓冲
    会静默漏收期次——fail-closed 拒,不放行带病回放。
    """
    tf = (timeframe or "").strip()
    if len(tf) < 2 or tf[-1] not in _TF_UNIT_SECONDS:
        raise ValueError(f"unsupported timeframe for PERP funding replay: {timeframe!r}")
    try:
        n = int(tf[:-1])
    except ValueError as e:
        raise ValueError(f"unsupported timeframe for PERP funding replay: {timeframe!r}") from e
    if n <= 0:
        raise ValueError(f"unsupported timeframe for PERP funding replay: {timeframe!r}")
    seconds = n * _TF_UNIT_SECONDS[tf[-1]]
    if seconds > _MAX_TIMEFRAME_SECONDS:
        raise ValueError(
            f"timeframe exceeds 24h funding look-ahead buffer for PERP funding replay: {timeframe!r}"
        )
    return seconds


def parse_instant(ts: str) -> datetime:
    """ISO-8601(Java Instant 序列化,含 Z 后缀)→ aware datetime(**归一 UTC**);非法抛 ValueError。

    归一(``astimezone(timezone.utc)``)保证下游 ``isoformat()`` 恒输出 ``+00:00`` 形态——
    事件 payload 的 Z 记法统一(event_loop ``replace("+00:00", "Z")``)对任意合法 offset
    输入都成立,不留混记法暗角。时刻值不变,aware 间比较语义不受影响。"""
    try:
        dt = datetime.fromisoformat(str(ts))
    except ValueError as e:
        raise ValueError(f"invalid timestamp for PERP backtest: {ts!r}") from e
    if dt.tzinfo is None:
        raise ValueError(f"timestamp without timezone rejected for PERP backtest: {ts!r}")
    return dt.astimezone(timezone.utc)


@dataclass
class PerpPosition:
    """净持仓簿记(spec §3.1)。CROSS 模式 margin 恒 0(账户担保不划转)。"""

    signed_qty: Decimal = Decimal(0)
    avg_price: Decimal | None = None
    leverage: int | None = None
    margin_mode: str | None = None
    margin: Decimal = Decimal(0)


@dataclass(frozen=True)
class LiquidationRecord:
    """强平成交记账明细(spec §4,引擎内部结构)。side = 被平方向(LONG/SHORT),
    qty/price 为全平量与近似成交价。面向策略的契约 payload 是 ``context.LiquidationEvent``
    (净额口径,由 event_loop 转换派发),两者不混用。"""

    timestamp: str
    position_side: str
    qty: Decimal
    price: Decimal
    fee: Decimal
    gross_pnl: Decimal
    margin_mode: str


@dataclass(frozen=True)
class FundingPeriod:
    """已结算资金费期次(funding_rates 序列行,worker 拉取后解析)。

    ``source``:EXCHANGE=本所 / PROXY_BINANCE=跨所代理(引擎统计进报告 warnings,
    显性标注基差风险,docs/perp-backtest-spec.md §8)。
    """

    funding_time: datetime
    settled_rate: Decimal
    interval_seconds: int | None
    mark_price: Decimal | None
    source: str = "EXCHANGE"


# ---------- 每标的净持仓的纯操作(单标的 PerpLedger 与组合 PerpPortfolioLedger 共用,spec §10.1) ----------


def decompose_segments(signed_qty: Decimal, position_effect: str, fill_qty: Decimal) -> list[tuple[bool, str, Decimal]]:
    """意图 → 内核段列表 ``[(is_open, side, qty), ...]``(反转拆 CLOSE+OPEN 两段,spec §3.3)。

    CLOSE_* 超仓/方向矛盾的合法性由闸门前置判定,本函数只在合法输入上工作。
    """
    d_sign = _EFFECT_DELTA_SIGN[position_effect]
    q = signed_qty
    if position_effect.startswith("CLOSE"):
        side = perp_math.SIDE_LONG if q > 0 else perp_math.SIDE_SHORT
        return [(False, side, fill_qty)]
    if q == 0 or (q > 0) == (d_sign > 0):
        side = perp_math.SIDE_LONG if d_sign > 0 else perp_math.SIDE_SHORT
        return [(True, side, fill_qty)]
    # 异号:|d| ≤ |q| 减仓;穿零反转拆两段(先全平再开反向超出量)
    side = perp_math.SIDE_LONG if q > 0 else perp_math.SIDE_SHORT
    if fill_qty <= abs(q):
        return [(False, side, fill_qty)]
    flip_side = perp_math.SIDE_SHORT if q > 0 else perp_math.SIDE_LONG
    return [(False, side, abs(q)), (True, flip_side, fill_qty - abs(q))]


def apply_segments_to_position(
    pos: PerpPosition,
    position_effect: str,
    fill_qty: Decimal,
    fill_price: Decimal,
    eff_leverage: int | None,
    eff_margin_mode: str | None,
) -> Decimal:
    """把一笔(闸门已放行的)成交按段序应用到单个净持仓(**原地修改 pos**),返毛 PnL。

    cash/realized_pnl 由调用方在账户级更新(单标的即账户本身,组合是共享现金池)。
    """
    gross = Decimal(0)
    for is_open, side, seg_qty in decompose_segments(pos.signed_qty, position_effect, fill_qty):
        delta = perp_math.apply_position_delta(
            position_side=side,
            is_open=is_open,
            current_qty=abs(pos.signed_qty),
            current_avg_entry_price=pos.avg_price,
            current_frozen_margin=pos.margin,
            fill_qty=seg_qty,
            fill_price=fill_price,
            leverage=eff_leverage,
        )
        gross += delta.realized_pnl_delta
        pos.signed_qty = delta.new_qty if side == perp_math.SIDE_LONG else -delta.new_qty
        pos.avg_price = delta.new_avg_entry_price
        if eff_margin_mode == "ISOLATED":
            pos.margin += delta.margin_delta
        pos.leverage = eff_leverage
        pos.margin_mode = eff_margin_mode
    return gross


def gate_position(
    pos: PerpPosition,
    cash: Decimal,
    sum_other_margin: Decimal,
    *,
    position_effect: str,
    fill_qty: Decimal,
    fill_price: Decimal,
    fee: Decimal,
    leverage: int | None,
    margin_mode: str | None,
) -> str | None:
    """单个净持仓的账本闸门(spec §6 步骤 2 / §10.3)。返 None=放行;否则拒单原因(warning 文案)。

    ``sum_other_margin``:账户内**其余**持仓的锁定保证金之和(单标的恒 0;组合传 Σ 其他仓 margin)。
    闸门用账户级 available' = predicted_cash − (sum_other_margin + 本仓 projected_margin) ≥ 0。
    顺序:穿蚀仓拒 → 持仓一致性(leverage/margin_mode)→ CLOSE 合法性(reduceOnly)→ 现金闸门。
    """
    if position_effect not in _EFFECT_DELTA_SIGN:
        return f"order rejected (unsupported positionEffect {position_effect})"
    is_flat = pos.signed_qty == 0
    if pos.margin < 0:
        # 穿蚀仓(spec §3.3):资金费把 ISOLATED 保证金侵蚀为负 → 主动订单一律拒,仅 §4 强平退出
        return (
            f"order rejected (MARGIN_DEPLETED: position margin eroded to {norm(pos.margin)}, "
            f"exits via liquidation only)"
        )
    if not is_flat:
        if leverage is not None and leverage != pos.leverage:
            return f"order rejected (LEVERAGE_MISMATCH: position leverage is {pos.leverage}, got {leverage})"
        if margin_mode is not None and margin_mode != pos.margin_mode:
            return (
                f"order rejected (MARGIN_MODE_MISMATCH: position marginMode is {pos.margin_mode}, "
                f"got {margin_mode})"
            )
    q = pos.signed_qty
    d_sign = _EFFECT_DELTA_SIGN[position_effect]
    if position_effect.startswith("CLOSE"):
        # reduceOnly:平仓不得反手/超仓(flat、方向矛盾、超量一律拒)。先于 leverage resolve。
        if q == 0 or (q > 0) != (d_sign < 0) or fill_qty > abs(q):
            return f"order rejected (CLOSE_OVER_POSITION: {position_effect} qty={fill_qty} against position {q})"
        return None  # 合法 CLOSE 无现金闸门(reduceOnly 平仓交易所不拒)
    eff_leverage = pos.leverage if not is_flat else leverage
    eff_margin_mode = pos.margin_mode if not is_flat else margin_mode
    if eff_leverage is None or eff_margin_mode is None:
        return "order rejected (leverage and marginMode required to open a position)"
    segments = decompose_segments(pos.signed_qty, position_effect, fill_qty)
    if not any(is_open for is_open, _, _ in segments):
        # 纯减仓无现金闸门(亏损实现是账务事实,cash 可为负)
        return None
    # 现金闸门:预测段序执行后账户 available' = predicted_cash − (其余仓 margin + 本仓 projected_margin) ≥ 0。
    # CROSS 存量 margin 恒 0,projected 起点 = 当前 margin,公式与 ISOLATED 统一(spec §6 步骤 2)。
    predicted_cash = cash - fee
    projected_margin = pos.margin
    cur_qty, cur_avg, cur_margin = abs(q), pos.avg_price, pos.margin
    for is_open, side, seg_qty in segments:
        if is_open:
            projected_margin += perp_math.initial_margin(fill_price, seg_qty, eff_leverage)
        else:
            release = perp_math.frozen_margin_release(cur_margin, cur_qty, seg_qty)
            predicted_cash += perp_math.closed_pnl(side, cur_avg, fill_price, seg_qty)
            projected_margin -= release
            cur_qty -= seg_qty
            cur_margin -= release
    if predicted_cash - (sum_other_margin + projected_margin) < 0:
        shortfall = norm(sum_other_margin + projected_margin - predicted_cash)
        return f"order rejected (insufficient margin: need {shortfall} more for {position_effect})"
    return None


class PerpLedger:
    """单标的 PERP 回测账本:cash/margin 双轨 + 净持仓(spec §3)。

    逐 bar 编排顺序由 event_loop 保证(spec §6):强平 → (acceptance/撮合/闸门见 event_loop)
    → :meth:`apply_fill` → 资金费 :meth:`settle_funding_period` → :meth:`equity`。
    """

    def __init__(
        self,
        *,
        initial_capital: Decimal,
        taker_fee_rate: Decimal,
        maint_margin_rate: Decimal | None = None,
    ) -> None:
        self.cash = initial_capital
        self.taker_fee_rate = taker_fee_rate
        self.mmr = maint_margin_rate if maint_margin_rate is not None else perp_math.DEFAULT_MAINT_MARGIN_RATE
        self.pos = PerpPosition()
        self.realized_pnl = Decimal(0)  # 净额口径(毛 PnL − fee),报告用
        self.funding_cum = Decimal(0)
        self.funding_periods_settled = 0
        self.liquidations: list[LiquidationRecord] = []

    # ---------- 读取 ----------

    @property
    def margin_used(self) -> Decimal:
        return self.pos.margin

    def available(self) -> Decimal:
        return self.cash - self.pos.margin

    def is_flat(self) -> bool:
        return self.pos.signed_qty == 0

    def position_side(self) -> str:
        """当前净持仓方向(LONG/SHORT);flat 时抛 ValueError(调用方先 is_flat 守卫)。"""
        if self.pos.signed_qty > 0:
            return perp_math.SIDE_LONG
        if self.pos.signed_qty < 0:
            return perp_math.SIDE_SHORT
        raise ValueError("position_side() on flat position")

    def unrealized(self, close: Decimal) -> Decimal:
        """未实现盈亏(mark-to-market,内核 closed_pnl 同式;flat 返 0)。"""
        if self.is_flat():
            return Decimal(0)
        return perp_math.closed_pnl(self.position_side(), self.pos.avg_price, close, abs(self.pos.signed_qty))

    def equity(self, close: Decimal) -> Decimal:
        return self.cash + self.unrealized(close)

    def liquidation_reference_price(self) -> Decimal | None:
        """ISOLATED 强平参考价(展示用,可 ≤0=资金费穿蚀);CROSS/flat 返 None(与 paper 口径一致)。"""
        if self.is_flat() or self.pos.margin_mode != "ISOLATED":
            return None
        return perp_math.liquidation_price_isolated(
            self.pos.avg_price, abs(self.pos.signed_qty), self.pos.margin, self.mmr, self.position_side()
        )

    # ---------- 闸门与分解(spec §3.3) ----------

    def decompose(self, position_effect: str, fill_qty: Decimal) -> list[tuple[bool, str, Decimal]]:
        """意图 → 内核段列表(委托 :func:`decompose_segments`,与组合账本共用单源)。"""
        return decompose_segments(self.pos.signed_qty, position_effect, fill_qty)

    def gate(
        self,
        *,
        position_effect: str,
        fill_qty: Decimal,
        fill_price: Decimal,
        fee: Decimal,
        leverage: int | None,
        margin_mode: str | None,
    ) -> str | None:
        """账本闸门(spec §6 步骤 2)。委托 :func:`gate_position`,单标的无"其余仓"故 sum_other_margin=0。"""
        return gate_position(
            self.pos,
            self.cash,
            Decimal(0),
            position_effect=position_effect,
            fill_qty=fill_qty,
            fill_price=fill_price,
            fee=fee,
            leverage=leverage,
            margin_mode=margin_mode,
        )

    # ---------- 应用 ----------

    def apply_fill(
        self,
        *,
        position_effect: str,
        fill_qty: Decimal,
        fill_price: Decimal,
        fee: Decimal,
        leverage: int | None,
        margin_mode: str | None,
    ) -> None:
        """应用一笔(gate 已放行的)成交:委托 :func:`apply_segments_to_position`(与组合账本共用单源),
        毛 PnL/手续费在账户级(cash/realized_pnl)入账。trade 记录由 event_loop 按用户视角一条写入。
        """
        eff_leverage = self.pos.leverage if not self.is_flat() else leverage
        eff_margin_mode = self.pos.margin_mode if not self.is_flat() else margin_mode
        gross = apply_segments_to_position(self.pos, position_effect, fill_qty, fill_price, eff_leverage, eff_margin_mode)
        self.cash += gross - fee
        self.realized_pnl += gross - fee

    # ---------- 强平(spec §4) ----------

    def check_liquidation(self, *, timestamp: str, open_: Decimal, high: Decimal, low: Decimal) -> LiquidationRecord | None:
        """bar 极值强平判定与成交(在本 bar 撮合**之前**调用,spec §4.1 规则 4)。

        触发用内核 margin_breached 谓词(不用价格比较——ISOLATED 资金费穿蚀后参考价可 ≤0);
        成交价:开盘跳空亦破 → open,否则 ISOLATED 参考价 / CROSS 触发极值(保守近似)。
        触发即全平并原地记账,返回事件;未触发返 None。
        """
        if self.is_flat():
            return None
        side = self.position_side()
        q = abs(self.pos.signed_qty)
        extreme = low if side == perp_math.SIDE_LONG else high
        if not self._breached_at(extreme, side, q):
            return None
        if self._breached_at(open_, side, q):
            exec_price = open_  # 跳空穿越:按 open 成交(滑点真实性)
        elif self.pos.margin_mode == "ISOLATED":
            ref = perp_math.liquidation_price_isolated(self.pos.avg_price, q, self.pos.margin, self.mmr, side)
            exec_price = ref if ref > 0 else extreme  # ref ≤0 且 open 未破在数学上不可达,防御走极值
        else:
            exec_price = extreme  # CROSS 无单一参考价:触发极值成交(文档化保守近似)

        fee = (exec_price * q * self.taker_fee_rate).quantize(_SCALE_8, rounding=ROUND_HALF_UP)
        # 毛 PnL 走内核 closed_pnl(EXACT);记账不走 apply_position_delta CLOSE 段——内核校验
        # currentFrozenMargin 非负,而资金费穿蚀后 margin 可为负(spec §3.3)。全平时内核释放
        # = 全部 margin,与直接清零等价(负 margin 的亏空已经由资金费入账反映在 cash,守恒不破)。
        gross = perp_math.closed_pnl(side, self.pos.avg_price, exec_price, q)
        # 穿仓(gross < −margin)从 cash 扣穿:无保险基金/ADL(spec §4.1 规则 5)
        self.cash += gross - fee
        self.realized_pnl += gross - fee
        margin_mode = self.pos.margin_mode
        self.pos = PerpPosition()
        event = LiquidationRecord(
            timestamp=timestamp,
            position_side=side,
            qty=q,
            price=exec_price,
            fee=fee,
            gross_pnl=gross,
            margin_mode=margin_mode or "",
        )
        self.liquidations.append(event)
        return event

    def _breached_at(self, price: Decimal, side: str, qty: Decimal) -> bool:
        """price 处是否穿仓:ISOLATED 仓位级 margin+unrealized,CROSS 账户级 cash+unrealized。"""
        base = self.pos.margin if self.pos.margin_mode == "ISOLATED" else self.cash
        margin_balance = base + perp_math.closed_pnl(side, self.pos.avg_price, price, qty)
        maint = perp_math.maintenance_margin_required(price, qty, self.mmr)
        return perp_math.margin_breached(margin_balance, maint)

    # ---------- 资金费(spec §5) ----------

    def settle_funding_period(self, settled_rate: Decimal, mark_price: Decimal) -> Decimal:
        """结算一期资金费(调用方保证期次归属与顺序)。flat 返 0 跳过;返本期金额(持仓视角)。

        ``mark_price`` 是结算 mark 价(spec §5.3 真值化:调用方优先传期次行自带交易所真值,
        行缺失 fallback 归属 bar close——fallback 决策在调用方,本方法只消费)。
        ISOLATED 侵蚀/增厚仓位保证金(cash 与 margin 同增同减,available 不变);
        CROSS 仅入 cash(账户担保)。
        """
        if self.is_flat():
            return Decimal(0)
        side = self.position_side()
        f = perp_math.funding_amount(side, settled_rate, mark_price, abs(self.pos.signed_qty))
        self.cash += f
        if self.pos.margin_mode == "ISOLATED":
            self.pos.margin += f
        self.funding_cum += f
        self.funding_periods_settled += 1
        return f


class FundingReplay:
    """资金费期次回放游标(spec §5.1,左开右闭归属 + catch-up)。

    periods 必须 ASC(Java 端点保证);:meth:`periods_for_bar` 结算所有未消费且
    ``funding_time ≤ bar_open + timeframe`` 的期次。连续时间轴下等价于
    ``funding_time ∈ (bar_open, bar_open + timeframe]``(== bar_open 的期次已被上一根的
    右闭边界消费);时间轴断档(交易所停摆/数据缺口)时漏期由下一根存在的 bar **catch-up
    补结**(与 paper 资金费调度的 catch-up 语义一致,绝不静默漏收)。首根 bar 的
    ``funding_time == bar_open`` 边界期归首根(此时通常 flat,不收费,无害)。
    游标单调不回退(时间轴倒退防御:不重复收费)。
    """

    def __init__(self, periods: list[FundingPeriod], timeframe: str) -> None:
        self._periods = periods
        self._bar_span = timedelta(seconds=timeframe_seconds(timeframe))
        self._idx = 0

    def periods_for_bar(self, bar_open_ts: str) -> list[FundingPeriod]:
        end = parse_instant(bar_open_ts) + self._bar_span
        out: list[FundingPeriod] = []
        while self._idx < len(self._periods) and self._periods[self._idx].funding_time <= end:
            out.append(self._periods[self._idx])
            self._idx += 1
        return out

    @property
    def pending_count(self) -> int:
        return len(self._periods) - self._idx

    def pending_periods(self) -> list[FundingPeriod]:
        """未被任何 bar 归属窗消费的剩余期次(ASC)。

        run() 尾部诊断用:K 线提前结束(klines actualEnd < 任务 end)时,落在最后一根 bar
        归属窗之后的已结算期次永不参与回放——按任务 end 过滤后进 warnings 显性标注
        (docs/perp-backtest-spec.md §5"绝不静默漏收"承诺的收尾防线)。"""
        return self._periods[self._idx :]


class PerpPortfolioLedger:
    """组合(多标的)PERP 账户账本(spec §10):共享 ``cash`` + per-symbol 净持仓。

    每标的净持仓的应用规则/加权均价/穿零反转/闸门与单标的 :class:`PerpLedger` **逐字一致**
    (复用 :func:`decompose_segments` / :func:`apply_segments_to_position` / :func:`gate_position`
    + ``perp_math`` 内核);账户级扩展:共享现金池、CROSS 保证金聚合(隔离 ISOLATED 锁定额,
    §10.2)、Model B 单腿脉冲强平(§10.4)。N=1 逐字退化为单标的 PerpLedger(差分测试锁定)。
    """

    def __init__(
        self,
        *,
        initial_capital: Decimal,
        taker_fee_rate: Decimal,
        maint_margin_rate: Decimal | None = None,
    ) -> None:
        self.cash = initial_capital
        self.taker_fee_rate = taker_fee_rate
        self.mmr = maint_margin_rate if maint_margin_rate is not None else perp_math.DEFAULT_MAINT_MARGIN_RATE
        self.positions: dict[str, PerpPosition] = {}
        self.realized_pnl = Decimal(0)
        self.funding_cum = Decimal(0)
        self.funding_periods_settled = 0
        self.liquidations: list[LiquidationRecord] = []

    # ---------- 读取 ----------

    def position(self, symbol: str) -> PerpPosition:
        """该标的净持仓(无仓返 flat 瞬态,不写入 dict)。"""
        return self.positions.get(symbol) or PerpPosition()

    def is_flat(self, symbol: str) -> bool:
        p = self.positions.get(symbol)
        return p is None or p.signed_qty == 0

    def sum_iso_margin(self) -> Decimal:
        """Σ 所有 ISOLATED 持仓锁定保证金(CROSS 恒 0,§3.1)。"""
        return sum((p.margin for p in self.positions.values() if p.margin_mode == "ISOLATED"), Decimal(0))

    def available(self) -> Decimal:
        """账户可用 = cash − Σ_iso_margin(§10.2)。"""
        return self.cash - self.sum_iso_margin()

    @staticmethod
    def _pos_unrealized(pos: PerpPosition, mark: Decimal) -> Decimal:
        if pos.signed_qty == 0:
            return Decimal(0)
        side = perp_math.SIDE_LONG if pos.signed_qty > 0 else perp_math.SIDE_SHORT
        return perp_math.closed_pnl(side, pos.avg_price, mark, abs(pos.signed_qty))

    def unrealized(self, symbol: str, mark: Decimal) -> Decimal:
        p = self.positions.get(symbol)
        return self._pos_unrealized(p, mark) if p else Decimal(0)

    def equity(self, marks: dict[str, Decimal]) -> Decimal:
        """账户权益 = cash + Σ_all 持仓 unrealized(各标 mark)(§10.2)。"""
        total = self.cash
        for sym, p in self.positions.items():
            if p.signed_qty != 0:
                total += self._pos_unrealized(p, marks[sym])
        return total

    def liquidation_reference_price(self, symbol: str) -> Decimal | None:
        """ISOLATED 强平参考价(展示用,可 ≤0);CROSS/flat 返 None(与单标的口径一致)。"""
        p = self.positions.get(symbol)
        if p is None or p.signed_qty == 0 or p.margin_mode != "ISOLATED":
            return None
        side = perp_math.SIDE_LONG if p.signed_qty > 0 else perp_math.SIDE_SHORT
        return perp_math.liquidation_price_isolated(p.avg_price, abs(p.signed_qty), p.margin, self.mmr, side)

    # ---------- 闸门 / 应用(每标的净持仓,复用共用纯函数) ----------

    def gate(
        self,
        symbol: str,
        *,
        position_effect: str,
        fill_qty: Decimal,
        fill_price: Decimal,
        fee: Decimal,
        leverage: int | None,
        margin_mode: str | None,
    ) -> str | None:
        """账户级账本闸门(§10.3):available' = cash' − (其余仓 margin + 本仓 projected_margin) ≥ 0。"""
        pos = self.positions.get(symbol) or PerpPosition()
        sum_other = self.sum_iso_margin() - pos.margin  # CROSS pos.margin 恒 0,减 0 无害
        return gate_position(
            pos,
            self.cash,
            sum_other,
            position_effect=position_effect,
            fill_qty=fill_qty,
            fill_price=fill_price,
            fee=fee,
            leverage=leverage,
            margin_mode=margin_mode,
        )

    def apply_fill(
        self,
        symbol: str,
        *,
        position_effect: str,
        fill_qty: Decimal,
        fill_price: Decimal,
        fee: Decimal,
        leverage: int | None,
        margin_mode: str | None,
    ) -> None:
        """应用一笔(gate 已放行的)成交到该标的净持仓;毛 PnL/fee 账户级入账(共享 cash)。"""
        pos = self.positions.get(symbol)
        if pos is None:
            pos = PerpPosition()
            self.positions[symbol] = pos
        is_flat = pos.signed_qty == 0
        eff_leverage = pos.leverage if not is_flat else leverage
        eff_margin_mode = pos.margin_mode if not is_flat else margin_mode
        gross = apply_segments_to_position(pos, position_effect, fill_qty, fill_price, eff_leverage, eff_margin_mode)
        self.cash += gross - fee
        self.realized_pnl += gross - fee

    # ---------- 资金费(每标的,§10.5) ----------

    def settle_funding_period(self, symbol: str, settled_rate: Decimal, mark_price: Decimal) -> Decimal:
        """结算某标的一期资金费(flat 返 0)。ISOLATED cash+margin 同增减 / CROSS 仅 cash(§5.4)。"""
        pos = self.positions.get(symbol)
        if pos is None or pos.signed_qty == 0:
            return Decimal(0)
        side = perp_math.SIDE_LONG if pos.signed_qty > 0 else perp_math.SIDE_SHORT
        f = perp_math.funding_amount(side, settled_rate, mark_price, abs(pos.signed_qty))
        self.cash += f
        if pos.margin_mode == "ISOLATED":
            pos.margin += f
        self.funding_cum += f
        self.funding_periods_settled += 1
        return f

    # ---------- 强平(Model B,§10.4) ----------

    def liquidate(
        self, timestamp: str, bar_marks: dict[str, tuple[Decimal | None, Decimal | None, Decimal | None, Decimal]]
    ) -> list[tuple[str, LiquidationRecord]]:
        """账户强平判定与成交(§10.4)。``bar_marks[sym] = (open, high, low, close)``;open/high/low
        为 None = 该标本步无新 bar(stale,持 close,不参与脉冲)。**每个非 flat 持仓的 sym 必须在
        bar_marks 内**(引擎为 stale 标的填 close)。先 ISOLATED 逐仓(顺序无关,改 cash/iso_margin →
        影响 CROSS free_backing),后 CROSS 账户级 Model B。返 [(symbol, record), ...] ASC-ish。"""
        out: list[tuple[str, LiquidationRecord]] = []
        for sym in sorted(self.positions):
            pos = self.positions[sym]
            if pos.signed_qty == 0 or pos.margin_mode != "ISOLATED":
                continue
            rec = self._maybe_liquidate_isolated(sym, pos, bar_marks[sym], timestamp)
            if rec is not None:
                out.append((sym, rec))
        out.extend(self._maybe_liquidate_cross(bar_marks, timestamp))
        return out

    def _breached_iso(self, pos: PerpPosition, price: Decimal) -> bool:
        """ISOLATED 仓位级穿仓谓词:margin_balance = margin + unrealized(price)。"""
        mb = pos.margin + self._pos_unrealized(pos, price)
        maint = perp_math.maintenance_margin_required(price, abs(pos.signed_qty), self.mmr)
        return perp_math.margin_breached(mb, maint)

    def _maybe_liquidate_isolated(self, sym, pos, marks, timestamp) -> LiquidationRecord | None:
        o, h, l, c = marks
        side = perp_math.SIDE_LONG if pos.signed_qty > 0 else perp_math.SIDE_SHORT
        extreme = (l if side == perp_math.SIDE_LONG else h)
        if extreme is None:  # stale:无 intrabar 极值,用 close(仅捕获跨步资金费侵蚀等)
            extreme = c
        if not self._breached_iso(pos, extreme):
            return None
        if o is not None and self._breached_iso(pos, o):
            exec_price = o  # 开盘跳空穿越:按 open 成交
        else:
            ref = perp_math.liquidation_price_isolated(pos.avg_price, abs(pos.signed_qty), pos.margin, self.mmr, side)
            exec_price = ref if ref > 0 else extreme
        return self._close_liquidate(sym, pos, exec_price, timestamp)

    def _cross_positions(self) -> list[tuple[str, PerpPosition]]:
        return [(s, p) for s, p in self.positions.items() if p.signed_qty != 0 and p.margin_mode == "CROSS"]

    def _cross_breached_at(self, cross, prices) -> bool:
        free_backing = self.cash - self.sum_iso_margin()
        mb = free_backing + sum((self._pos_unrealized(p, prices[s]) for s, p in cross), Decimal(0))
        maint = sum(
            (perp_math.maintenance_margin_required(prices[s], abs(p.signed_qty), self.mmr) for s, p in cross),
            Decimal(0),
        )
        return perp_math.margin_breached(mb, maint)

    def _first_breaching_scenario(self, cross, bar_marks):
        """驱动情景(§10.4):gap 优先,其后 adverse[s] 按 s 升序;第一个穿仓的情景价向量。无则 None。"""
        gap = {}
        for s, p in cross:
            o, _h, _l, c = bar_marks[s]
            gap[s] = o if o is not None else c
        scenarios = [gap]
        for s, p in sorted(cross, key=lambda kv: kv[0]):
            o, h, l, c = bar_marks[s]
            if o is None:
                continue  # stale 标的不参与脉冲(无 intrabar 极值)
            spike = l if p.signed_qty > 0 else h
            adverse = {}
            for s2, _p2 in cross:
                _o2, _h2, _l2, c2 = bar_marks[s2]
                adverse[s2] = spike if s2 == s else c2
            scenarios.append(adverse)
        for prices in scenarios:
            if self._cross_breached_at(cross, prices):
                return prices
        return None

    def _maybe_liquidate_cross(self, bar_marks, timestamp) -> list[tuple[str, LiquidationRecord]]:
        """驱动情景一旦穿仓,全平所有 CROSS 仓(账户级,§10.4)。bar 近似下单仓清算优先级无干净
        定义(崩盘腿价低 → maint 反而小,"最大 maint 优先"会先平健康腿),故不做单仓渐进——
        账户已穿仓即全平是保守且确定的简化,与单标的全平语义一致。symbol 升序记录,N=1 退化为单标的。"""
        cross = self._cross_positions()
        if not cross:
            return []
        driving = self._first_breaching_scenario(cross, bar_marks)
        if driving is None:
            return []
        return [
            (sym, self._close_liquidate(sym, pos, driving[sym], timestamp))
            for sym, pos in sorted(cross, key=lambda kv: kv[0])
        ]

    def _close_liquidate(self, sym, pos, exec_price, timestamp) -> LiquidationRecord:
        """全平一个持仓(强平记账,与单标的 check_liquidation 同口径:毛 PnL 走内核 closed_pnl,
        margin 全清,穿仓从 cash 扣穿——无保险基金/ADL)。返记录并把该 sym 置 flat。"""
        side = perp_math.SIDE_LONG if pos.signed_qty > 0 else perp_math.SIDE_SHORT
        q = abs(pos.signed_qty)
        fee = (exec_price * q * self.taker_fee_rate).quantize(_SCALE_8, rounding=ROUND_HALF_UP)
        gross = perp_math.closed_pnl(side, pos.avg_price, exec_price, q)
        self.cash += gross - fee
        self.realized_pnl += gross - fee
        margin_mode = pos.margin_mode
        self.positions[sym] = PerpPosition()
        rec = LiquidationRecord(
            timestamp=timestamp,
            position_side=side,
            qty=q,
            price=exec_price,
            fee=fee,
            gross_pnl=gross,
            margin_mode=margin_mode or "",
        )
        self.liquidations.append(rec)
        return rec
