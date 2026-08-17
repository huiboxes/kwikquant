"""函数式策略 ctx + 数据类(回测/Runner 共用)。

用户写顶层函数 ``def on_bar(bar, ctx)``,ctx 提供:
- ``history(field, n)``:切片内存 K 线(由 event_loop set),返 ``list[float]`` 含当前 bar
- ``place_order(side, order_type, amount, price=None)``:回测中排队至下一 bar 本地撮合 / Runner 实盘下单
- ``position(symbol)``:账本持仓
- ``log(msg)``:stderr 日志
- ``symbol``:当前交易对

**平台核心纯标准库,不绑定 numpy/pandas**(用户想用自行 import;平台 requirements 预装方便,
但不作为依赖)。金额红线:行情(open/high/low/close/volume)用 ``float``(非金额,用户直接算术);
下单 amount/price 用户传 float/str/Decimal 都行,边界 ``_to_decimal`` 转 Decimal;账本(qty/price/fee)Decimal。
"""

from __future__ import annotations

import sys
from dataclasses import dataclass
from decimal import Decimal, InvalidOperation
from typing import TYPE_CHECKING

from kwikquant_worker.backtest.matching import OrderIntent, _ORDER_TYPES

if TYPE_CHECKING:
    from kwikquant.client import Client


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
    symbol: str
    qty: Decimal
    avg_price: Decimal


def _to_decimal(v: Decimal | float | int | str, field: str) -> Decimal:
    """下单金额边界 Decimal 化:用户传 float/str/Decimal/int 都兼容(非法值抛 ValueError fail-closed)。"""
    if isinstance(v, Decimal):
        return v
    try:
        return Decimal(str(v))
    except (InvalidOperation, ValueError) as e:
        raise ValueError(f"place_order {field} 非法: {v!r}") from e


class BacktestContext:
    """回测 ctx:event_loop 逐 bar ``set_klines/set_index``,策略 on_bar 内读历史 + 下单。

    ``history`` 切片 ``_klines`` 内存(零额外请求/缓存概念);``place_order`` 将订单意图排队
    (``_pending``),event_loop 在**下一 bar** 用本地撮合引擎(``backtest/matching.py``,NEXT_BAR
    语义,docs/matching-spec.md §7)撮合并应用成交;``_apply_fill`` 维护持仓均价。

    回测撮合已本地化(Wave 2.2):place_order 不再发 HTTP,账本充足性闸门由 event_loop 在
    应用成交前检查(原 Java 回测账本 canApply 语义)。
    """

    def __init__(
        self,
        client: "Client",
        task_id: int,
        *,
        exchange: str = "BINANCE",
        market_type: str = "SPOT",
        symbol: str = "",
    ) -> None:
        self._client = client
        self._task_id = task_id
        self._exchange = exchange
        self._market_type = market_type
        self._symbol = symbol
        self._klines: list[dict] = []
        self._index: int = -1
        self._pending: list[OrderIntent] = []
        self._positions: dict[str, Position] = {}

    def set_klines(self, klines: list[dict]) -> None:
        self._klines = klines

    def set_index(self, i: int) -> None:
        self._index = i

    @property
    def symbol(self) -> str:
        return self._symbol

    def history(self, field: str, n: int) -> list[float]:
        """最近 n 根(含当前 bar)K 线的 field 值,``list[float]``。

        不足 n 根(开头 warmup)返已有;index 未 set 返 []。field ∈ open/high/low/close/volume。
        """
        if self._index < 0 or not self._klines:
            return []
        start = max(0, self._index - n + 1)
        return [float(str(k[field])) for k in self._klines[start : self._index + 1]]

    def place_order(
        self,
        *,
        side: str,
        order_type: str,
        amount: Decimal | float | str,
        price: Decimal | float | str | None = None,
    ) -> None:
        """回测下单:校验后入 ``_pending`` 队列,event_loop 下一 bar 本地撮合(NEXT_BAR)。

        返 None(成交由 event_loop 应用,``position()`` 查持仓)。校验 fail-closed(抛
        ValueError),对应原 Java 契约反序列化 400:side ∈ BUY/SELL;order_type 属已知枚举
        (条件单可提交但内核不主动触发,docs/matching-spec.md §3);amount > 0;price(如提供)> 0。
        """
        if side not in ("BUY", "SELL"):
            raise ValueError(f"place_order side 非法: {side!r}(应 BUY/SELL)")
        if order_type not in _ORDER_TYPES:
            raise ValueError(f"place_order order_type 非法: {order_type!r}")
        amt = _to_decimal(amount, "amount")
        if amt <= 0:
            raise ValueError(f"place_order amount 必须 > 0: {amount!r}")
        px = _to_decimal(price, "price") if price is not None else None
        if px is not None and px <= 0:
            raise ValueError(f"place_order price 必须 > 0: {price!r}")
        self._pending.append(
            OrderIntent(symbol=self._symbol, side=side, order_type=order_type, amount=amt, price=px)
        )
        return None

    def take_pending(self) -> list[OrderIntent]:
        """event_loop 每 bar 开头取走上一 bar 积累的订单意图(清空队列)。"""
        intents, self._pending = self._pending, []
        return intents

    def position(self, symbol: str) -> Position:
        return self._positions.get(symbol, Position(symbol=symbol, qty=Decimal(0), avg_price=Decimal(0)))

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
