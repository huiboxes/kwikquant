"""重复下单防护惯用法的差分锁死(docs/strategy-api.md「重复下单防护」小节)。

守卫骨架被 12 个官方模板与文档"照抄"指引共享,小节的三条行为声明在此机器锁死
(仓库"语义由差分测试锁住"惯例,防引擎时序演进后声明静默失效):

1. 成交路径与无守卫逐字节一致——回测撮合落账在 on_bar 之前,意向次 bar 即决,
   守卫在成交路径上从不拦截(guard on/off 的 trades+equity_curve 全等);
2. 恒定性拒单只漂移 warnings 节奏——拒单不移动持仓时 trades/equity 仍全等,
   重试从每 bar 一次变为每 PENDING_TIMEOUT 根一次(拒单 warnings 变少);
   价格时变拒单会移动成交时点(声明已如实区分,不在此锁);
3. `!=` 判定对 PERP signed 空头成立——开空/平空/强平归零都离开基线,
   而 `qty > base` 方向式判定对开空腿是永假式(锁死 F3 修复,防回退)。
"""

from __future__ import annotations

from decimal import Decimal
from unittest.mock import MagicMock

from kwikquant_worker.event_loop import BacktestEventLoop
from kwikquant_worker.strategy import BacktestContext


def _klines(n: int, price: int = 100):
    """n 根小时 bar,close 恒定——不触发价格时变拒单,保证"恒定性"前提。"""
    return [
        {
            "timestamp": f"2024-01-01T{h:02d}:00:00Z",
            "open": str(price),
            "high": str(price + 1),
            "low": str(price - 1),
            "close": str(price),
            "volume": "10",
        }
        for h in range(n)
    ]


class _Guard:
    """文档小节守卫骨架的逐字同构(module 级 global → 实例属性,语义一比一)。"""

    PENDING_TIMEOUT = 3

    def __init__(self):
        self._pending_base = None
        self._pending_bars = 0

    def allows(self, pos):
        if self._pending_base is None:
            return True
        self._pending_bars += 1
        if pos.qty != self._pending_base or self._pending_bars >= self.PENDING_TIMEOUT:
            self._pending_base, self._pending_bars = None, 0
            return True
        return False

    def mark(self, pos):
        self._pending_base, self._pending_bars = pos.qty, 0


class _Pos:
    def __init__(self, qty):
        self.qty = qty


def _run(on_bar, klines):
    ctx = BacktestContext(MagicMock(), task_id=1, symbol="BTC/USDT")
    loop = BacktestEventLoop(initial_capital=Decimal("10000"), symbol="BTC/USDT", timeframe="1h")
    return loop.run(on_bar, ctx, klines)


def test_fill_path_guard_on_off_trades_and_equity_identical():
    """声明 1:一进一出(BUY@bar1 → 次 bar 成交;SELL@bar4 → 次 bar 成交),
    guard on/off 的 trades 与 equity_curve 逐字节全等,且确实发生两笔成交。"""
    results = {}
    for guarded in (False, True):
        guard = _Guard()
        ticks = {"n": 0}

        def on_bar(bar, ctx, guarded=guarded, guard=guard, ticks=ticks):
            ticks["n"] += 1
            pos = ctx.position(ctx.symbol)
            tradable = guard.allows(pos) if guarded else True
            if ticks["n"] == 1 and pos.qty <= 0 and tradable:
                ack = ctx.place_order(side="BUY", order_type="MARKET", amount=Decimal("0.1"))
                if guarded and ack.accepted:
                    guard.mark(pos)
            elif ticks["n"] == 4 and pos.qty > 0 and tradable:
                ack = ctx.place_order(side="SELL", order_type="MARKET", amount=pos.qty)
                if guarded and ack.accepted:
                    guard.mark(pos)

        results[guarded] = _run(on_bar, _klines(8))

    assert results[True]["trades"] == results[False]["trades"]
    assert results[True]["equity_curve"] == results[False]["equity_curve"]
    assert len(results[True]["trades"]) == 2  # 进场+离场真实发生,等价声明非空谈


def test_constant_rejection_trades_equal_warnings_rhythm_shifts():
    """声明 2:BUY 成交后每 bar 尝试超仓 SELL(库存闸门恒拒,价格无关),
    trades/equity 全等(仅 BUY 一笔),守卫把拒单重试从每 bar 压到每 3 根。"""
    results = {}
    for guarded in (False, True):
        guard = _Guard()
        ticks = {"n": 0}

        def on_bar(bar, ctx, guarded=guarded, guard=guard, ticks=ticks):
            ticks["n"] += 1
            pos = ctx.position(ctx.symbol)
            tradable = guard.allows(pos) if guarded else True
            if ticks["n"] == 1 and pos.qty <= 0 and tradable:
                ack = ctx.place_order(side="BUY", order_type="MARKET", amount=Decimal("0.1"))
                if guarded and ack.accepted:
                    guard.mark(pos)
            elif pos.qty > 0 and tradable:
                # 恒定拒单:SELL 超持仓(远超 dust 容差),库存闸门每次拒,持仓不动
                ack = ctx.place_order(side="SELL", order_type="MARKET", amount=pos.qty + Decimal("0.05"))
                if guarded and ack.accepted:
                    guard.mark(pos)

        results[guarded] = _run(on_bar, _klines(8))

    on, off = results[True], results[False]
    assert on["trades"] == off["trades"]
    assert on["equity_curve"] == off["equity_curve"]
    assert len(on["trades"]) == 1  # 只有 BUY 成交
    rej_on = [w for w in on["warnings"] if "insufficient inventory" in w]
    rej_off = [w for w in off["warnings"] if "insufficient inventory" in w]
    assert rej_off, "无守卫版应每 bar 重下重拒"
    assert rej_on, "守卫版超时放行后仍会重试重拒"
    assert len(rej_on) < len(rej_off)  # 重试节奏 每 PENDING_TIMEOUT 根 vs 每 bar


def test_ne_check_covers_signed_perp_short_lifecycle():
    """声明 3:PERP signed 净持仓下 != 判定的三条成交确认通道全部成立。"""
    # 开空腿:0 → 负,离开基线(qty > base 方向式对此永假,意向只能悬挂到超时)
    g = _Guard()
    g.mark(_Pos(Decimal("0")))
    assert g.allows(_Pos(Decimal("-0.01"))) is True
    # 平空腿:负 → 0
    g = _Guard()
    g.mark(_Pos(Decimal("-0.01")))
    assert g.allows(_Pos(Decimal("0"))) is True
    # 强平归零与平空同形
    g = _Guard()
    g.mark(_Pos(Decimal("-0.05")))
    assert g.allows(_Pos(Decimal("0"))) is True
    # 反例锁死:方向式判定(ENTRY=qty>base)对开空腿永不确认——文档警告的由来
    assert not (Decimal("-0.01") > Decimal("0"))


def test_timeout_release_and_partial_fill_settle():
    """超时放行节奏(mark 当 bar 不计,第 PENDING_TIMEOUT 次 tick 放行)与
    部分成交即决(离开基线,剩余量不补单)。"""
    g = _Guard()
    g.mark(_Pos(Decimal("0")))
    assert g.allows(_Pos(Decimal("0"))) is False  # bar+1 拦
    assert g.allows(_Pos(Decimal("0"))) is False  # bar+2 拦
    assert g.allows(_Pos(Decimal("0"))) is True   # bar+3 满 PENDING_TIMEOUT 放行重试
    # 部分成交:0 → 0.004 离开基线即视为已决
    g = _Guard()
    g.mark(_Pos(Decimal("0")))
    assert g.allows(_Pos(Decimal("0.004"))) is True
    # Decimal 尾零/scale 不造成假"离开"(数值比较,非字符串比较)
    g = _Guard()
    g.mark(_Pos(Decimal("0.10")))
    assert g.allows(_Pos(Decimal("0.1"))) is False
