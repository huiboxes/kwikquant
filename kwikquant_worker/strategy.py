"""Strategy 基类 + StrategyContext(Wave 8 §3.5)。

用户继承 ``Strategy``,重写 ``on_bar/on_tick/on_fill``,通过 ``self.buy/self.sell/self.sell_all``
提交订单;所有下单委托给注入的 ``StrategyContext``(测试可 mock)。
"""

from __future__ import annotations

from dataclasses import dataclass
from decimal import Decimal
from typing import TYPE_CHECKING, Any

if TYPE_CHECKING:
    from kwikquant.client import Client


@dataclass
class Bar:
    """§3.5 数据格式消歧:Bar 镜像 Kline。"""

    timestamp: str
    open: Decimal
    high: Decimal
    low: Decimal
    close: Decimal
    volume: Decimal


@dataclass
class Tick:
    timestamp: str
    bid: Decimal
    ask: Decimal
    last: Decimal


@dataclass
class Fill:
    """§1.5 镜像 trading/domain/Fill。"""

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


class StrategyContext:
    """封装下单/持仓查询。回测和 Runner 用不同 ctx 实现。"""

    def submit_order(
        self,
        *,
        symbol: str,
        side: str,
        order_type: str,
        amount: Decimal,
        price: Decimal | None = None,
    ) -> Fill | None:
        raise NotImplementedError

    def position(self, symbol: str) -> Position:
        raise NotImplementedError


class BacktestContext(StrategyContext):
    """回测 ctx — 走 :func:`Client.trade.submit_backtest`,携带 snapshot。"""

    def __init__(self, client: "Client", task_id: int) -> None:
        self._client = client
        self._task_id = task_id
        self._current_snapshot: dict | None = None
        self._positions: dict[str, Position] = {}

    def set_snapshot(self, snapshot: dict) -> None:
        """event_loop 每 bar 更新;submit_order 用它。"""
        self._current_snapshot = snapshot

    def submit_order(
        self,
        *,
        symbol: str,
        side: str,
        order_type: str,
        amount: Decimal,
        price: Decimal | None = None,
    ) -> Fill | None:
        if self._current_snapshot is None:
            raise ValueError("BacktestContext.submit_order called before set_snapshot")
        resp = self._client.trade.submit_backtest(
            self._task_id,
            symbol=symbol,
            side=side,
            order_type=order_type,
            amount=amount,
            price=price,
            snapshot=self._current_snapshot,
        )
        if resp is None:
            return None
        fill = Fill(
            order_id=int(resp["orderId"]),
            symbol=resp.get("symbol", symbol),
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

    def _apply_fill(self, fill: Fill) -> None:
        pos = self._positions.get(fill.symbol, Position(fill.symbol, Decimal(0), Decimal(0)))
        signed_qty = fill.qty if fill.side == "BUY" else -fill.qty
        new_qty = pos.qty + signed_qty
        # 简化的持仓均价(不做反向平仓 pnl):
        if pos.qty == 0 or (pos.qty > 0) != (new_qty > 0):
            avg = fill.price if new_qty != 0 else Decimal(0)
        else:
            avg = (pos.qty * pos.avg_price + signed_qty * fill.price) / new_qty if new_qty != 0 else Decimal(0)
        self._positions[fill.symbol] = Position(fill.symbol, new_qty, avg)


class Strategy:
    """用户策略基类。继承并重写 ``on_bar/on_tick/on_fill``。"""

    def __init__(self, ctx: StrategyContext, *, default_symbol: str = "") -> None:
        self.ctx = ctx
        self.default_symbol = default_symbol
        self.parameters: dict[str, Any] = {}

    # --- 用户 override ---
    def on_bar(self, bar: Bar) -> None:  # noqa: B027 — 空实现
        """回测/Runner 每 bar 触发。"""

    def on_tick(self, tick: Tick) -> None:  # noqa: B027
        """Runner 每 tick 触发。"""

    def on_fill(self, fill: Fill) -> None:  # noqa: B027
        """撮合成交回调。回测同步、Runner 异步(WS)。默认空。"""

    # --- 下单快捷方式(策略内调用)---
    def buy(
        self,
        *,
        symbol: str | None = None,
        amount: Decimal | float | str | None = None,
        price: Decimal | float | str | None = None,
        order_type: str = "MARKET",
    ) -> Fill | None:
        return self._submit("BUY", symbol, amount, price, order_type)

    def sell(
        self,
        *,
        symbol: str | None = None,
        amount: Decimal | float | str | None = None,
        price: Decimal | float | str | None = None,
        order_type: str = "MARKET",
    ) -> Fill | None:
        return self._submit("SELL", symbol, amount, price, order_type)

    def sell_all(self, *, symbol: str | None = None) -> Fill | None:
        sym = symbol or self.default_symbol
        pos = self.ctx.position(sym)
        if pos.qty == 0:
            return None
        return self.sell(symbol=sym, amount=abs(pos.qty))

    def _submit(
        self,
        side: str,
        symbol: str | None,
        amount: Any,
        price: Any,
        order_type: str,
    ) -> Fill | None:
        sym = symbol or self.default_symbol
        if not sym:
            raise ValueError("symbol required (or set default_symbol)")
        if amount is None:
            raise ValueError(f"amount required for {side}")
        dec_amount = amount if isinstance(amount, Decimal) else Decimal(str(amount))
        dec_price = None if price is None else (price if isinstance(price, Decimal) else Decimal(str(price)))
        return self.ctx.submit_order(
            symbol=sym, side=side, order_type=order_type, amount=dec_amount, price=dec_price
        )
