"""策略契约单一真相源 — StrategyContext Protocol + OrderAck + 共享入参校验。

三个运行时(单标的回测 ``strategy.BacktestContext`` / 组合回测 ``portfolio.PortfolioContext`` /
模拟盘实盘 ``runner_context.RunnerContext``)共享本契约:签名由 Protocol 结构类型描述,
入参校验由 :func:`normalize_order` 单源实现,漂移由差分测试锁死
(``tests/python/test_context_contract.py``)。用户策略文档见 ``docs/strategy-api.md``。

**金额红线**:amount/price 只收 ``Decimal/str/int``,**拒 float**(TypeError)——float 残差
(如 0.1+0.2=0.30000000000000004)会踩穿库存闸门造成"满仓平不掉"或灰尘仓,从入口消灭;
行情(``history`` 返回的 OHLCV)维持 float(非金额,策略直接算术)。

**四向语义**(PERP,docs/perp-backtest-spec.md §2):``position_effect`` 必填四向,``side``
禁传(由 effect 派生,与 Java ``Order.validate`` 四象限/回测 acceptance §9 同一张表
``acceptance.EFFECT_TO_SIDE``),入口消灭 side/effect 双源矛盾。

**异常语义**(三运行时一致):契约违规(类型/枚举/值域非法)抛 ValueError/TypeError——
回测 fail-fast 整个任务失败,runner 记 stderr 继续(模式本质差异,能力矩阵见 strategy-api.md);
业务拒单(接受性/账本闸门/风控)不是异常——回测进报告 warnings,runner 经 OrderAck.accepted=False。
"""

from __future__ import annotations

from dataclasses import dataclass
from decimal import Decimal, InvalidOperation
from typing import TYPE_CHECKING, Any, Mapping, Protocol, runtime_checkable

from kwikquant_worker.acceptance import EFFECT_TO_SIDE
from kwikquant_worker.backtest.matching import _ORDER_TYPES

if TYPE_CHECKING:
    from kwikquant_worker.strategy import Position

# SELL/Close 库存闸门 dust 容差(docs/matching-spec.md §7):差额小于该值视为全平意图,
# clamp 到账本原值。消灭 Decimal 运算残差(如 100/3 的 28 位商再舍入)造成的假拒单。
DUST_TOLERANCE = Decimal("1e-12")

# predicted_funding_rate 运行时能力分叉的单源拒绝文案(回测/组合 ctx 抛,runner 实现)。
PREDICTED_FUNDING_RUNNER_ONLY = "预估资金费仅 runner 可用（回测无对应真值，喂入即 lookahead；docs/strategy-api.md §9）"


@dataclass(frozen=True)
class FillEvent:
    """成交事件 payload(策略顶层可选回调 ``on_fill(fill, ctx)``,docs/strategy-api.md §8)。

    回测:引擎在 BAR 节点撮合应用后逐笔同步派发(order_id 是引擎内部序号,非平台订单 id);
    runner:经 WS ``/topic/fills/{userId}`` 异步派发(按绑定 accountId+marketType+symbol 过滤;
    金额字段防御性 ``Decimal(str(v))`` 转换,WS number/补拉 decimal string 双形态兼容),
    断线窗口的 on_fill 由 ``GET /api/v1/worker/fills-since`` 周期补拉兜底(fillId 去重,
    进程内 exactly-once,重启不回放,docs/strategy-api.md §8)。强平成交**不**派发本事件
    (走 :class:`LiquidationEvent`,两侧通道互斥)。
    """

    symbol: str
    side: str  # 回测=大写 BUY/SELL、runner=小写 buy/sell(跨运行时存量漂移,统一另批);PERP 是派生量,开平语义看 position_effect
    price: Decimal
    qty: Decimal
    fee: Decimal
    fee_currency: str  # 空串 = symbol 无合法 quote 段(不可推导)
    filled_at: str  # ISO-8601
    order_id: int | None = None  # 回测=引擎内部序号;runner=平台 orderId
    liquidity: str | None = None  # taker / maker
    position_effect: str | None = None  # PERP 四向;SPOT 恒 None


@dataclass(frozen=True)
class FundingEvent:
    """资金费结算事件 payload(策略顶层可选回调 ``on_funding(ev, ctx)``,仅 PERP)。

    回测:引擎在 FUNDING 节点逐期派发(精确 ``funding_time``;flat 期次不结算不派发,
    docs/perp-backtest-spec.md §5);runner:经 WS ``/topic/funding/{userId}`` 异步派发。
    """

    symbol: str
    funding_time: str  # 精确结算时刻 ISO-8601 Z 记法(可落在 bar 中段;与 filled_at/bar.timestamp 同记法)
    settled_rate: Decimal | None  # 期次费率(runner LIVE 账单来源可为 None)
    amount: Decimal  # 本期金额(持仓视角,正=收 负=付)
    qty_at_settle: Decimal  # 结算时持仓量(|signed qty|;runner 载荷缺失时为 0——零仓本不派发,0 即缺失)
    mark_price: Decimal | None = None  # 回测=实际结算 mark(期次行真值,fallback 归属 bar close);runner 恒 None
    source: str | None = None  # 回测=EXCHANGE/PROXY_BINANCE;runner 恒 None


@dataclass(frozen=True)
class LiquidationEvent:
    """强平事件 payload(策略顶层可选回调 ``on_liquidation(ev, ctx)``,仅 PERP)。

    回测:引擎在强平成交后派发(bar 极值近似,docs/perp-backtest-spec.md §4);runner:经 WS
    ``/topic/liquidations/{userId}`` 异步派发。与 :class:`FillEvent` 通道互斥(强平不双派
    on_fill,对齐 Java 侧 /topic/liquidations 与 /topic/fills 的推送互斥)。

    命名注记:引擎内部记账结构是 ``backtest/perp_ledger.LiquidationRecord``(强平成交明细,
    含 gross_pnl/fee),本类是面向策略的契约 payload(净额口径),两者不混用。
    """

    symbol: str
    timestamp: str
    position_side: str  # 被平方向 LONG/SHORT
    qty: Decimal  # 强平数量(绝对值;回测=全平量,runner=本次实际平仓量)
    price: Decimal | None  # 强平成交价(回测=bar 极值近似价恒有值;runner=liquidationPrice 派生失败可 null)
    realized_pnl: Decimal | None = None  # 回测=净额(毛 PnL − fee);runner=该持仓已实现盈亏
    margin_mode: str | None = None  # ISOLATED/CROSS(runner legacy 桶行可空)
    reason: str | None = None  # runner=触发原因文案;回测恒 None


@dataclass(frozen=True)
class OrderAck:
    """``place_order``/``close_position`` 统一回执(三运行时同构)。

    - ``accepted``:请求被受理。回测=通过契约校验并已排队(NEXT_BAR:撮合发生在下一 bar,
      接受性/账本闸门的拒单**异步**进报告 warnings,不反映在本回执);runner=平台已受理
      (HTTP 成功,风控/冻结已过),网络失败或业务拒单为 False + reason。
    - ``reason``:未受理原因(accepted=True 时为 None)。
    - ``filled_qty``/``filled_price``:**提交时点**成交信息。回测恒 None(NEXT_BAR 成交在
      下一 bar);runner PAPER 提交时为 Decimal("0")(撮合由行情推送异步驱动)、LIVE 为
      None(交易所异步回报)。**不要以 filled_qty 判成交**:提交成功看 ``accepted``,
      成交结果查 ``position()``(runner 另有 /topic/fills 推送)。
    """

    accepted: bool
    reason: str | None = None
    filled_qty: Decimal | None = None
    filled_price: Decimal | None = None


@dataclass(frozen=True)
class NormalizedOrder:
    """:func:`normalize_order` 的规范化输出(side 已派生,金额已 Decimal 化)。"""

    side: str
    order_type: str
    amount: Decimal
    price: Decimal | None
    position_effect: str | None
    leverage: int | None
    margin_mode: str | None


def to_decimal(v: Decimal | int | str, field: str) -> Decimal:
    """下单金额边界 Decimal 化:收 Decimal/str/int;**拒 float**(TypeError,金额红线);
    非法值抛 ValueError fail-closed。与撮合内核 ``matching._dec`` 同一纪律。"""
    if isinstance(v, Decimal):
        return v
    if isinstance(v, float):
        raise TypeError(
            f"place_order {field} 拒绝 float: {v!r}"
            "(金额红线——float 残差会踩库存闸门;请用 str/Decimal,如 '0.01' 或 Decimal('0.01'))"
        )
    try:
        return Decimal(str(v))
    except (InvalidOperation, ValueError, TypeError) as e:
        raise ValueError(f"place_order {field} 非法: {v!r}") from e


def to_leverage(v: int | str) -> int:
    """leverage 边界 int 化(str/int 兼容,float 与非法值抛 ValueError fail-closed)。

    范围校验(1-100 与 per-symbol 上限)不在 ctx 层——属接受性规则
    (docs/matching-spec.md §9 规则 12/16/17),由引擎撮合前闸门统一判定进 warnings。
    """
    if isinstance(v, float):
        raise ValueError(f"place_order leverage 非法(float 拒绝): {v!r}")
    try:
        return int(v)
    except (TypeError, ValueError) as e:
        raise ValueError(f"place_order leverage 非法: {v!r}") from e


def normalize_order(
    *,
    market_type: str,
    side: str | None = None,
    order_type: str,
    amount: Decimal | int | str,
    price: Decimal | int | str | None = None,
    position_effect: str | None = None,
    leverage: int | str | None = None,
    margin_mode: str | None = None,
) -> NormalizedOrder:
    """三 ctx 共享的下单入参校验(契约单一真相源)。违规抛 ValueError/TypeError(fail-closed)。

    - order_type ∈ 已知枚举。条件单类(STOP_*/TAKE_PROFIT_*)回测契约不带 stop_price 入参:
      pairSpecs 下发时被接受性层拒(STOP_PRICE_REQUIRED 进 warnings),未下发时撮合内核
      不主动触发(fill 返 None 进 warnings)——回测中永不成交,止损请用市价单自管
      (docs/matching-spec.md §3);
    - amount Decimal 化(拒 float)且 > 0;price(如提供)同纪律且 > 0;
    - **SPOT**:side 必填 ∈ BUY/SELL;合约字段(position_effect/leverage/margin_mode)禁传
      ——与接受性规则 19 同构;
    - **PERP**(docs/perp-backtest-spec.md §2):position_effect 必填四向;side **禁传**
      (由 effect 派生);margin_mode ∈ ISOLATED/CROSS;leverage int 化(范围校验在接受性层)。
    """
    if order_type not in _ORDER_TYPES:
        raise ValueError(f"place_order order_type 非法: {order_type!r}")
    amt = to_decimal(amount, "amount")
    if amt <= 0:
        raise ValueError(f"place_order amount 必须 > 0: {amount!r}")
    px = to_decimal(price, "price") if price is not None else None
    if px is not None and px <= 0:
        raise ValueError(f"place_order price 必须 > 0: {price!r}")
    if market_type == "PERP":
        if side is not None:
            raise ValueError(
                f"place_order PERP 禁传 side: {side!r}(side 由 position_effect 派生,不接受双源)"
            )
        if position_effect not in EFFECT_TO_SIDE:
            raise ValueError(
                f"place_order position_effect 非法: {position_effect!r}"
                "(PERP 应 OPEN_LONG/OPEN_SHORT/CLOSE_LONG/CLOSE_SHORT)"
            )
        if margin_mode is not None and margin_mode not in ("ISOLATED", "CROSS"):
            raise ValueError(f"place_order margin_mode 非法: {margin_mode!r}(应 ISOLATED/CROSS)")
        lev = None if leverage is None else to_leverage(leverage)
        return NormalizedOrder(
            side=EFFECT_TO_SIDE[position_effect],
            order_type=order_type,
            amount=amt,
            price=px,
            position_effect=position_effect,
            leverage=lev,
            margin_mode=margin_mode,
        )
    # SPOT:合约字段禁传(镜像接受性规则 19,ctx 层 fail-fast)
    if position_effect is not None or leverage is not None or margin_mode is not None:
        raise ValueError("place_order SPOT 不得传 position_effect/leverage/margin_mode(合约字段仅 PERP)")
    if side not in ("BUY", "SELL"):
        raise ValueError(f"place_order side 非法: {side!r}(应 BUY/SELL)")
    return NormalizedOrder(
        side=side, order_type=order_type, amount=amt, price=px,
        position_effect=None, leverage=None, margin_mode=None,
    )


def clamp_dust_close(held: Decimal, want: Decimal) -> Decimal | None:
    """库存闸门 dust 容差判定(matching-spec §7):``held > 0`` 且超出量 ``< DUST_TOLERANCE``
    → 返回 clamp 后的全平数量(=held);否则返 None(走正常拒单)。"""
    if held > 0 and want > held and (want - held) < DUST_TOLERANCE:
        return held
    return None


@runtime_checkable
class StrategyContext(Protocol):
    """策略 ctx 统一契约(三运行时结构一致;能力差异见 docs/strategy-api.md 矩阵)。

    结构型 Protocol:实现类不继承本类,签名漂移由
    ``isinstance(ctx, StrategyContext)``(runtime_checkable 查方法存在性)+ 差分测试拦截。
    """

    @property
    def params(self) -> Mapping[str, Any]:
        """任务/策略绑定参数(只读;回测=提交 parameters,runner=bootstrap parameters)。"""
        ...

    @property
    def symbol(self) -> str:
        """绑定交易对(组合 ctx 返回空串,逐单显式传 symbol)。"""
        ...

    def place_order(
        self,
        *,
        symbol: str | None = None,
        side: str | None = None,
        order_type: str,
        amount: Decimal | int | str,
        price: Decimal | int | str | None = None,
        position_effect: str | None = None,
        leverage: int | None = None,
        margin_mode: str | None = None,
    ) -> OrderAck:
        """下单(单标的/runner ctx 可省 symbol;组合 ctx 必传)。校验见 :func:`normalize_order`。"""
        ...

    def close_position(self, symbol: str | None = None) -> OrderAck:
        """市价全平当前持仓(**用账本原值下单**,绕开一切精度残差)。无持仓返
        ``OrderAck(accepted=False, reason="NO_POSITION")``。"""
        ...

    def position(self, symbol: str | None = None) -> "Position":
        """账本持仓(副本,防策略篡改)。PERP qty 为 signed 净持仓(正=LONG 负=SHORT)。"""
        ...

    def equity(self) -> Decimal:
        """账户权益(quote 计)。回测直读引擎账本;runner 经绑定账户余额 REST 合成。"""
        ...

    def available_cash(self) -> Decimal:
        """可用现金/保证金(quote 计)。PERP=现金−锁定保证金。"""
        ...

    def history(self, field: str, n: int, symbol: str | None = None) -> list[float]:
        """最近 n 根(含当前)K 线的 field 值(float 行情,非金额)。组合 ctx 必传 symbol。"""
        ...

    def cancel(self, order_id: int) -> None:
        """撤单。回测 no-op(限价单单根 bar 自动过期);runner 真撤(失败吞掉记 stderr)。"""
        ...

    def report_progress(self, processed: int, total: int) -> None:
        """进度上报(仅回测 task 有进度概念;runner no-op)。"""
        ...

    def log(self, msg: str) -> None:
        """策略日志(stderr)。"""
        ...

    def predicted_funding_rate(self, symbol: str | None = None) -> Decimal | None:
        """当期预估资金费率(**仅 runner**,PERP;带符号,正=多头付空头收)。

        运行时能力**有意分叉**(差分测试锁定,非遗漏):单标的回测与组合回测抛
        ``NotImplementedError``——预估是"指向未来结算时刻的当期累计值",喂回测即 lookahead bias
        (docs/strategy-api.md §9,回测/实盘的已知差异,不声称等价)。runner 数据不可得
        (非 PERP / 网络失败)返 ``None``,与 ``position()`` 查询失败同纪律,绝不造值。"""
        ...
