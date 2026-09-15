"""函数式策略 ctx + 数据类(回测/Runner 共用)。

用户写顶层函数 ``def on_bar(bar, ctx)``,ctx 契约单一真相源在 ``context.py``
(:class:`~kwikquant_worker.context.StrategyContext` Protocol,用户文档 docs/strategy-api.md):

- ``params``:任务 parameters(只读 Mapping;worker exec 前同步注入模块级 ``PARAMS``)
- ``history(field, n)``:切片内存 K 线(由 event_loop set),返 ``list[float]`` 含当前 bar
- ``place_order(...)`` -> ``OrderAck``:回测中排队至下一 bar 撮合(NEXT_BAR) / Runner 实盘下单
- ``close_position()`` -> ``OrderAck``:市价全平(用账本原值下单,绕开精度残差)
- ``position(symbol=None)``:账本持仓(PERP qty 为 signed 净持仓)
- ``equity()`` / ``available_cash()``:直读引擎账本(策略无需也不应 float 自记账)
- ``cancel(order_id)``:撤销挂单。回测中未成交限价单单根 bar 自动过期,故为 no-op;
  Runner 实盘中调 DELETE /api/v1/orders/{id}(被动限价策略每根 bar 撤旧挂新用)
- ``log(msg)``:stderr 日志
- ``symbol``:当前交易对

可选模块级常量 ``WARMUP_BARS = N``:Runner 启动时经 REST 回填最近 N 根已关闭 K 线到
``history``(只灌历史不触发 on_bar;回测天然全量预载,此常量对回测无影响)。不定义则不回填,
实盘启动初期 history 为空。上限 999(REST 单次 limit 1000,需留 1 根给进行中的活 bar)。

**平台核心纯标准库,不绑定 numpy/pandas**(用户想用自行 import;平台 requirements 预装方便,
但不作为依赖)。金额红线:行情(open/high/low/close/volume)用 ``float``(非金额,用户直接算术);
下单 amount/price 只收 ``Decimal/str/int``,**拒 float**(TypeError,context.to_decimal——
float 残差会踩库存闸门);账本(qty/price/fee)Decimal。
"""

from __future__ import annotations

import sys
from dataclasses import dataclass, replace
from decimal import Decimal
from types import MappingProxyType
from typing import TYPE_CHECKING, Any, Mapping

from kwikquant_worker.backtest.matching import OrderIntent
from kwikquant_worker.context import PREDICTED_FUNDING_RUNNER_ONLY, OrderAck, normalize_order

if TYPE_CHECKING:
    from kwikquant.client import Client
    from kwikquant_worker.event_loop import BacktestEventLoop


@dataclass
class Bar:
    """单根 K 线(行情,float 非金额)。event_loop 每 bar 构造喂给 on_bar。"""

    timestamp: str
    open: float
    high: float
    low: float
    close: float
    volume: float


@dataclass
class Tick:
    """Runner 实盘 tick(行情,float)。"""

    timestamp: str
    bid: float
    ask: float
    last: float


@dataclass
class Fill:
    """成交回报(金额 Decimal,镜像 trading/domain/Fill)。"""

    order_id: int
    symbol: str
    side: str
    price: Decimal
    qty: Decimal
    fee: Decimal
    fee_currency: str
    filled_at: str


@dataclass
class Position:
    """账本持仓视图。SPOT:qty ≥ 0 现货数量;PERP 回测:qty 为 **signed 净持仓**
    (正=LONG 负=SHORT,docs/perp-backtest-spec.md §1),扩展字段仅 PERP 有值。"""

    symbol: str
    qty: Decimal
    avg_price: Decimal
    # ---- PERP 扩展(SPOT 恒 None) ----
    leverage: int | None = None
    margin_mode: str | None = None  # ISOLATED / CROSS
    margin: Decimal | None = None  # 仓位锁定保证金(ISOLATED 随资金费增减,可负;CROSS 恒 0)
    liquidation_price: Decimal | None = None  # ISOLATED 参考价(可 ≤0=资金费穿蚀);CROSS None
    unrealized_pnl: Decimal | None = None  # 最新 close 口径未实现盈亏


class BacktestContext:
    """回测 ctx:event_loop 逐 bar ``set_klines/set_index``,策略 on_bar 内读历史 + 下单。

    ``history`` 切片 ``_klines`` 内存(零额外请求/缓存概念);``place_order`` 将订单意图排队
    (``_pending``),event_loop 在**下一 bar** 用本地撮合引擎(``backtest/matching.py``,NEXT_BAR
    语义,docs/matching-spec.md §7)撮合并应用成交;``_apply_fill`` 维护持仓均价。

    回测撮合已本地化:place_order 不再发 HTTP,账本充足性闸门由 event_loop 在
    应用成交前检查(原 Java 回测账本 canApply 语义)。

    ``equity()``/``available_cash()`` 经 ``bind(loop)`` 直读引擎账本(run 前未绑定返 0)。
    """

    def __init__(
        self,
        client: "Client",
        task_id: int,
        *,
        exchange: str = "BINANCE",
        market_type: str = "SPOT",
        symbol: str = "",
        params: Mapping[str, Any] | None = None,
    ) -> None:
        self._client = client
        self._task_id = task_id
        self._exchange = exchange
        self._market_type = market_type
        self._symbol = symbol
        # 浅冻结:策略可读写内部可变对象的属性但不可增删键(与 module.PARAMS 同一实例语义)
        self._params: Mapping[str, Any] = MappingProxyType(dict(params or {}))
        self._klines: list[dict] = []
        self._index: int = -1
        self._pending: list[OrderIntent] = []
        self._positions: dict[str, Position] = {}
        self._loop: "BacktestEventLoop | None" = None

    # ---------- 引擎侧装配(非策略 API) ----------

    def bind(self, loop: "BacktestEventLoop") -> None:
        self._loop = loop

    def set_klines(self, klines: list[dict]) -> None:
        self._klines = klines

    def set_index(self, i: int) -> None:
        self._index = i

    def take_pending(self) -> list[OrderIntent]:
        """event_loop 每 bar 开头取走上一 bar 积累的订单意图(清空队列)。"""
        intents, self._pending = self._pending, []
        return intents

    # ---------- 策略 API(context.StrategyContext 契约) ----------

    @property
    def params(self) -> Mapping[str, Any]:
        return self._params

    @property
    def symbol(self) -> str:
        return self._symbol

    def _resolve_symbol(self, symbol: str | None) -> str:
        """单标的 ctx 的 symbol 归一:缺省用绑定 symbol;显式传入必须一致(fail-closed 防误用)。

        绑定为空(未指定 symbol 的测试形态)时宽容透传显式值。"""
        if symbol is None:
            return self._symbol
        if self._symbol and symbol != self._symbol:
            raise ValueError(
                f"单标的回测 ctx 绑定 {self._symbol!r},不接受其他 symbol: {symbol!r}"
            )
        return symbol

    def history(self, field: str, n: int, symbol: str | None = None) -> list[float]:
        """最近 n 根(含当前 bar)K 线的 field 值,``list[float]``。

        不足 n 根(开头 warmup)返已有;index 未 set 返 []。field ∈ open/high/low/close/volume。
        ``symbol`` 可省(单标的绑定);显式传入必须与绑定一致。
        """
        self._resolve_symbol(symbol)
        if self._index < 0 or not self._klines:
            return []
        start = max(0, self._index - n + 1)
        return [float(str(k[field])) for k in self._klines[start : self._index + 1]]

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
        """回测下单:校验后入 ``_pending`` 队列,event_loop 下一 bar 本地撮合(NEXT_BAR)。

        返 ``OrderAck(accepted=True)`` = 已通过契约校验并排队;成交发生在下一 bar,
        接受性/账本闸门的拒单**异步**进报告 warnings(不反映在回执),成交结果查
        ``position()``。校验 fail-closed(抛 ValueError/TypeError),共享单源
        ``context.normalize_order``:order_type 枚举;amount/price **拒 float**;
        SPOT side 必填且合约字段禁传;PERP position_effect 必填四向、side 禁传
        (docs/perp-backtest-spec.md §2),leverage/margin_mode 首仓必填(持仓可省略继承,
        矛盾由引擎闸门拒单进 warnings)。
        """
        sym = self._resolve_symbol(symbol)
        o = normalize_order(
            market_type=self._market_type,
            side=side,
            order_type=order_type,
            amount=amount,
            price=price,
            position_effect=position_effect,
            leverage=leverage,
            margin_mode=margin_mode,
        )
        self._pending.append(
            OrderIntent(
                symbol=sym,
                side=o.side,
                order_type=o.order_type,
                amount=o.amount,
                price=o.price,
                position_effect=o.position_effect,
                leverage=o.leverage,
                margin_mode=o.margin_mode,
            )
        )
        return OrderAck(accepted=True)

    def close_position(self, symbol: str | None = None) -> OrderAck:
        """市价全平当前持仓:**用账本原值下单**(Decimal 原样透传,从根上绕开精度残差)。

        无持仓返 ``OrderAck(accepted=False, reason="NO_POSITION")``(不下单,显式可见)。
        PERP 按 signed qty 方向派生 CLOSE_LONG/CLOSE_SHORT(leverage/margin_mode 省略,
        引擎从现有持仓继承)。排队语义同 place_order(NEXT_BAR)。
        """
        sym = self._resolve_symbol(symbol)
        pos = self.position(sym)
        if pos.qty == 0:
            return OrderAck(accepted=False, reason="NO_POSITION")
        if self._market_type == "PERP":
            effect = "CLOSE_LONG" if pos.qty > 0 else "CLOSE_SHORT"
            return self.place_order(order_type="MARKET", amount=abs(pos.qty), position_effect=effect)
        return self.place_order(side="SELL", order_type="MARKET", amount=pos.qty)

    def position(self, symbol: str | None = None) -> Position:
        # 返回副本:策略代码不可信,不能把账本的可变活引用暴露出去(防策略改写 qty/avg_price 污染账本)。
        sym = self._resolve_symbol(symbol)
        p = self._positions.get(sym)
        if p is None:
            return Position(symbol=sym, qty=Decimal(0), avg_price=Decimal(0))
        return replace(p)

    def equity(self) -> Decimal:
        """账户权益(quote 计)= 现金 + 持仓市值(PERP 为账本 equity 口径)。直读引擎账本;
        run 前未绑定引擎返 Decimal(0)。"""
        if self._loop is None:
            return Decimal(0)
        return self._loop.current_equity()

    def available_cash(self) -> Decimal:
        """可用现金(quote 计;PERP=现金−锁定保证金)。直读引擎账本;未绑定返 Decimal(0)。"""
        if self._loop is None:
            return Decimal(0)
        return self._loop.available_cash()

    def cancel(self, order_id: int) -> None:
        """回测 no-op:未成交限价单只在该 bar 有效(未触及不结转下一 bar),无需撤单。

        存在意义:与 RunnerContext.cancel 同签名,策略一份代码通吃回测/实盘
        (实盘每根 bar 撤旧挂新,回测自动过期天然等价)。
        """
        return None

    def log(self, msg: str) -> None:
        print(f"[strategy] {msg}", file=sys.stderr)

    def report_progress(self, processed: int, total: int) -> None:
        """逐 bar 进度上报(节流由 event_loop 控制,每 200 bar 或末根调)。

        失败容错:进度上报失败不能中断回测(仅进度展示降级为旋转 Loader),记 stderr。
        """
        try:
            self._client.trade.report_progress(self._task_id, processed, total)
        except Exception as e:  # noqa: BLE001 — 进度上报失败不阻断回测
            print(f"[ctx] report_progress failed: {e!r}", file=sys.stderr)

    def predicted_funding_rate(self, symbol: str | None = None) -> Decimal | None:
        """回测无预估资金费:抛 NotImplementedError(运行时能力分叉,docs/strategy-api.md §9)。"""
        raise NotImplementedError(PREDICTED_FUNDING_RUNNER_ONLY)

    def _apply_fill(self, fill: Fill) -> None:
        pos = self._positions.get(fill.symbol, Position(fill.symbol, Decimal(0), Decimal(0)))
        signed_qty = fill.qty if fill.side == "BUY" else -fill.qty
        new_qty = pos.qty + signed_qty
        if new_qty == 0:
            avg = Decimal(0)
        elif pos.qty == 0 or (pos.qty > 0) != (new_qty > 0):
            avg = fill.price
        elif (pos.qty > 0) != (signed_qty > 0):
            avg = pos.avg_price
        else:
            avg = (pos.qty * pos.avg_price + signed_qty * fill.price) / new_qty
        self._positions[fill.symbol] = Position(fill.symbol, new_qty, avg)
