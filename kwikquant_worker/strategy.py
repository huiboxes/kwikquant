"""函数式策略 ctx + 数据类(回测/Runner 共用)。

用户写顶层函数 ``def on_bar(bar, ctx):``,ctx 提供:
- ``history(field, n)``:切片内存 K 线(由 event_loop set),返 ``list[float]`` 含当前 bar
- ``place_order(side, order_type, amount, price=None)``:调 Java 撮合(回测) / 实盘下单(Runner)
- ``position(symbol)``:账本持仓
- ``log(msg)``:stderr 日志
- ``symbol``:当前交易对

**平台核心纯标准库,不绑定 numpy/pandas**(用户想用自行 import;平台 requirements 预装方便,
但不作为依赖)。金额红线:行情(open/high/low/close/volume)用 ``float``(非金额,用户直接算术);
下单 amount/price 用户传 float/str/Decimal 都行,边界 ``_bd`` 转 Decimal;账本(qty/price/fee)Decimal。
"""

from __future__ import annotations

import sys
from dataclasses import dataclass
from decimal import Decimal
from typing import TYPE_CHECKING

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


def _bd(v: Decimal | float | int | str | None) -> str | None:
    """金额 BigDecimal 字符串序列化(None 透传);用户传 float/str/Decimal 都兼容。"""
    if v is None:
        return None
    return str(v) if isinstance(v, Decimal) else str(Decimal(str(v)))


class BacktestContext:
    """回测 ctx:event_loop 逐 bar ``set_klines/set_index/set_snapshot``,策略 on_bar 内读历史 + 下单。

    ``history`` 切片 ``_klines`` 内存(零额外请求/缓存概念);``place_order`` 调 Java
    ``submit_backtest`` 撮合,返 Fill 或 None(未成交);``_apply_fill`` 维护持仓均价。
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
        self._current_snapshot: dict | None = None
        self._positions: dict[str, Position] = {}

    def set_klines(self, klines: list[dict]) -> None:
        self._klines = klines

    def set_index(self, i: int) -> None:
        self._index = i

    def set_snapshot(self, snapshot: dict) -> None:
        self._current_snapshot = snapshot

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
    ) -> Fill | None:
        if self._current_snapshot is None:
            raise ValueError("place_order called before event_loop set_snapshot")
        resp = self._client.trade.submit_backtest(
            self._task_id,
            symbol=self._symbol,
            side=side,
            order_type=order_type,
            amount=_bd(amount),
            price=_bd(price),
            snapshot=self._current_snapshot,
            market_type=self._market_type,
            exchange=self._exchange,
        )
        if resp is None:
            return None
        fill = Fill(
            order_id=int(resp["orderId"]),
            symbol=resp.get("symbol", self._symbol),
            side=resp.get("side", side),
            price=Decimal(str(resp["price"])),
            qty=Decimal(str(resp["qty"])),
            fee=Decimal(str(resp.get("fee", 0))),
            fee_currency=resp.get("feeCurrency", ""),
            filled_at=resp.get("filledAt", ""),
        )
        self._apply_fill(fill)
        return fill

    def position(self, symbol: str) -> Position:
        return self._positions.get(symbol, Position(symbol=symbol, qty=Decimal(0), avg_price=Decimal(0)))

    def log(self, msg: str) -> None:
        print(f"[strategy] {msg}", file=sys.stderr)

    def _apply_fill(self, fill: Fill) -> None:
        pos = self._positions.get(fill.symbol, Position(fill.symbol, Decimal(0), Decimal(0)))
        signed_qty = fill.qty if fill.side == "BUY" else -fill.qty
        new_qty = pos.qty + signed_qty
        if pos.qty == 0 or (pos.qty > 0) != (new_qty > 0):
            avg = fill.price if new_qty != 0 else Decimal(0)
        else:
            avg = (
                (pos.qty * pos.avg_price + signed_qty * fill.price) / new_qty
                if new_qty != 0
                else Decimal(0)
            )
        self._positions[fill.symbol] = Position(fill.symbol, new_qty, avg)
