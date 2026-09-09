"""组合(多标的)回测引擎测试 — PortfolioEventLoop + PortfolioContext。

覆盖:公共时间轴对齐/缺 bar、NEXT_BAR 逐标的语义、共享现金池闸门、
无未来数据、ctx.equity()/available_cash() 与引擎账本逐位一致、section8 输出结构。
"""

from __future__ import annotations

from decimal import Decimal
from unittest.mock import MagicMock, call

import pytest

from kwikquant_worker.portfolio import PortfolioContext, PortfolioEventLoop


def _k(closes: list[str], start: int = 0, step_prefix: str = "t") -> list[dict]:
    """按 close 序列构造 kline(Open=High=Low=Close 简化,ts=prefix+index)。"""
    return [
        {
            "timestamp": f"{step_prefix}{start + i:04d}",
            "open": c,
            "high": c,
            "low": c,
            "close": c,
            "volume": "1",
        }
        for i, c in enumerate(closes)
    ]


def _two_symbol_series():
    return {
        "AAA/USDT": _k(["100", "110", "120"]),
        "BBB/USDT": _k(["50", "55", "60"]),
    }


# ---------------------------------------------------------------- section8 结构


def test_portfolio_section8_shape():
    ctx = PortfolioContext(MagicMock(), task_id=1, symbols=["AAA/USDT", "BBB/USDT"])
    loop = PortfolioEventLoop(initial_capital=Decimal("10000"), symbols=["AAA/USDT", "BBB/USDT"], timeframe="1h")
    section8 = loop.run(lambda c: None, ctx, _two_symbol_series())

    assert section8["name"] == "portfolio_backtest"
    assert section8["symbol"] == "AAA/USDT,BBB/USDT"
    assert section8["symbols"] == ["AAA/USDT", "BBB/USDT"]
    assert section8["timeframe"] == "1h"
    assert section8["period"] == {"start": "t0000", "end": "t0002"}
    assert section8["trades"] == []
    assert section8["positions"] == {}
    assert len(section8["equity_curve"]) == 3
    for pt in section8["equity_curve"]:
        Decimal(pt["equity"])


def test_portfolio_flat_equity_without_trades():
    ctx = PortfolioContext(MagicMock(), task_id=1, symbols=["AAA/USDT", "BBB/USDT"])
    loop = PortfolioEventLoop(initial_capital=Decimal("10000"), symbols=["AAA/USDT", "BBB/USDT"])
    section8 = loop.run(lambda c: None, ctx, _two_symbol_series())
    assert [Decimal(p["equity"]) for p in section8["equity_curve"]] == [Decimal("10000")] * 3


# ---------------------------------------------------------------- 时间轴对齐 / 缺 bar


def test_portfolio_timeline_is_union_of_symbols():
    """AAA 有 t0/t2(缺 t1),BBB 有 t0/t1/t2 → 时间轴 = t0,t1,t2,权益曲线 3 点。"""
    # AAA 缺 t1(只有 t0/t2),BBB 全量
    series = {
        "AAA/USDT": [
            {"timestamp": "t0000", "open": "100", "high": "100", "low": "100", "close": "100", "volume": "1"},
            {"timestamp": "t0002", "open": "120", "high": "120", "low": "120", "close": "120", "volume": "1"},
        ],
        "BBB/USDT": _k(["50", "55", "60"]),
    }
    ctx = PortfolioContext(MagicMock(), task_id=1, symbols=["AAA/USDT", "BBB/USDT"])
    loop = PortfolioEventLoop(initial_capital=Decimal("10000"), symbols=["AAA/USDT", "BBB/USDT"])
    section8 = loop.run(lambda c: None, ctx, series)
    assert [p["time"] for p in section8["equity_curve"]] == ["t0000", "t0001", "t0002"]


def test_portfolio_bar_returns_none_when_symbol_missing_at_step():
    """AAA 缺 t1:该步 ctx.bar('AAA/USDT') 返 None,ctx.bar('BBB/USDT') 正常。"""
    series = {
        "AAA/USDT": [
            {"timestamp": "t0000", "open": "1", "high": "1", "low": "1", "close": "100", "volume": "1"},
            {"timestamp": "t0002", "open": "1", "high": "1", "low": "1", "close": "120", "volume": "1"},
        ],
        "BBB/USDT": _k(["50", "55", "60"]),
    }
    ctx = PortfolioContext(MagicMock(), task_id=1, symbols=["AAA/USDT", "BBB/USDT"])
    seen: dict[str, list] = {"AAA": [], "BBB": []}

    def on_bars(c):
        seen["AAA"].append(c.bar("AAA/USDT"))
        seen["BBB"].append(c.bar("BBB/USDT"))

    loop = PortfolioEventLoop(symbols=["AAA/USDT", "BBB/USDT"])
    loop.run(on_bars, ctx, series)
    assert [b.timestamp if b else None for b in seen["AAA"]] == ["t0000", None, "t0002"]
    assert [b.timestamp for b in seen["BBB"]] == ["t0000", "t0001", "t0002"]
    assert seen["AAA"][0].close == 100.0


def test_portfolio_history_has_no_future_data():
    """history 只到当前已收盘 bar:每步看到的各标的最大 timestamp ≤ 当前步(防未来数据)。"""
    series = _two_symbol_series()
    ctx = PortfolioContext(MagicMock(), task_id=1, symbols=["AAA/USDT", "BBB/USDT"])
    steps = []

    def on_bars(c):
        # history 长度 == 已收盘根数;最后一根的 ts 不应超过当前步(引擎以 ptr 推进保证)
        steps.append(
            {
                "a_hist": c.history("close", 100, symbol="AAA/USDT"),
                "b_hist": c.history("close", 100, symbol="BBB/USDT"),
            }
        )

    loop = PortfolioEventLoop(symbols=["AAA/USDT", "BBB/USDT"])
    loop.run(on_bars, ctx, series)
    assert [s["a_hist"] for s in steps] == [[100.0], [100.0, 110.0], [100.0, 110.0, 120.0]]
    assert [s["b_hist"] for s in steps] == [[50.0], [50.0, 55.0], [50.0, 55.0, 60.0]]


def test_portfolio_history_per_symbol_independent():
    """AAA 缺 t1:AAA 的 history 在 t2 只有 2 根(不含空洞),BBB 有 3 根。"""
    series = {
        "AAA/USDT": [
            {"timestamp": "t0000", "open": "1", "high": "1", "low": "1", "close": "100", "volume": "1"},
            {"timestamp": "t0002", "open": "1", "high": "1", "low": "1", "close": "120", "volume": "1"},
        ],
        "BBB/USDT": _k(["50", "55", "60"]),
    }
    ctx = PortfolioContext(MagicMock(), task_id=1, symbols=["AAA/USDT", "BBB/USDT"])
    final = {}

    def on_bars(c):
        final["a"] = c.history("close", 10, symbol="AAA/USDT")
        final["b"] = c.history("close", 10, symbol="BBB/USDT")

    loop = PortfolioEventLoop(symbols=["AAA/USDT", "BBB/USDT"])
    loop.run(on_bars, ctx, series)
    assert final["a"] == [100.0, 120.0]
    assert final["b"] == [50.0, 55.0, 60.0]


# ---------------------------------------------------------------- NEXT_BAR 与撮合


def test_portfolio_next_bar_fill_per_symbol():
    """t0 下 AAA 市价单 → t1 成交,成交价 = AAA t1 close × (1+5bps)(NEXT_BAR,逐标的)。"""
    ctx = PortfolioContext(MagicMock(), task_id=1, symbols=["AAA/USDT", "BBB/USDT"])

    def on_bars(c):
        if c.bar("AAA/USDT") and c.bar("AAA/USDT").timestamp == "t0000":
            c.place_order(symbol="AAA/USDT", side="BUY", order_type="MARKET", amount="1")

    loop = PortfolioEventLoop(initial_capital=Decimal("100000"), symbols=["AAA/USDT", "BBB/USDT"])
    section8 = loop.run(on_bars, ctx, _two_symbol_series())

    assert len(section8["trades"]) == 1
    tr = section8["trades"][0]
    assert tr["symbol"] == "AAA/USDT"
    assert tr["time"] == "t0001"
    assert Decimal(tr["price"]) == (Decimal("110") * Decimal("1.00050000")).quantize(Decimal("0.00000001"))
    assert tr["side"] == "buy"


def test_portfolio_pending_order_carries_over_missing_bars():
    """AAA 缺 t1:t0 下的 AAA 单结转,在 AAA 下一根可用 bar(t2)撮合,不在空洞步误撮合。"""
    series = {
        "AAA/USDT": [
            {"timestamp": "t0000", "open": "1", "high": "1", "low": "1", "close": "100", "volume": "1"},
            {"timestamp": "t0002", "open": "1", "high": "1", "low": "1", "close": "120", "volume": "1"},
        ],
        "BBB/USDT": _k(["50", "55", "60"]),
    }
    ctx = PortfolioContext(MagicMock(), task_id=1, symbols=["AAA/USDT", "BBB/USDT"])

    def on_bars(c):
        b = c.bar("AAA/USDT")
        if b and b.timestamp == "t0000":
            c.place_order(symbol="AAA/USDT", side="BUY", order_type="MARKET", amount="1")

    loop = PortfolioEventLoop(initial_capital=Decimal("100000"), symbols=["AAA/USDT", "BBB/USDT"])
    section8 = loop.run(on_bars, ctx, series)

    assert len(section8["trades"]) == 1
    tr = section8["trades"][0]
    assert tr["time"] == "t0002"  # AAA 的下一根可用 bar
    assert Decimal(tr["price"]) == (Decimal("120") * Decimal("1.00050000")).quantize(Decimal("0.00000001"))
    # t1 无 AAA bar → 无 AAA 成交;权益曲线仍 3 点
    assert [p["time"] for p in section8["equity_curve"]] == ["t0000", "t0001", "t0002"]


def test_portfolio_limit_not_crossed_warns_once_no_retry():
    """LIMIT 未穿越 → 该标的下一根 bar 撮合失败记 warning,不结转重试(与单标的一致)。"""
    ctx = PortfolioContext(MagicMock(), task_id=1, symbols=["AAA/USDT"])

    def on_bars(c):
        b = c.bar("AAA/USDT")
        if b and b.timestamp == "t0000":
            c.place_order(symbol="AAA/USDT", side="BUY", order_type="LIMIT", amount="1", price="1")

    loop = PortfolioEventLoop(initial_capital=Decimal("100000"), symbols=["AAA/USDT"])
    section8 = loop.run(on_bars, ctx, {"AAA/USDT": _k(["100", "110", "120"])})
    assert section8["trades"] == []
    assert any("place_order returned None" in w for w in section8["warnings"])


def test_portfolio_limit_crossed_fills_at_limit_price_maker():
    """LIMIT BUY 穿越(限价 ≥ 下一 bar low)→ 按限价 maker 成交。"""
    series = {
        "AAA/USDT": [
            {"timestamp": "t0000", "open": "100", "high": "101", "low": "99", "close": "100", "volume": "1"},
            {"timestamp": "t0001", "open": "100", "high": "105", "low": "98", "close": "104", "volume": "1"},
        ]
    }
    ctx = PortfolioContext(MagicMock(), task_id=1, symbols=["AAA/USDT"])

    def on_bars(c):
        if c.bar("AAA/USDT") and c.bar("AAA/USDT").timestamp == "t0000":
            c.place_order(symbol="AAA/USDT", side="BUY", order_type="LIMIT", amount="2", price="99")

    loop = PortfolioEventLoop(initial_capital=Decimal("100000"), symbols=["AAA/USDT"])
    section8 = loop.run(on_bars, ctx, series)
    assert len(section8["trades"]) == 1
    tr = section8["trades"][0]
    assert Decimal(tr["price"]) == Decimal("99.00000000")
    # maker 费率 0.001:99 × 2 × 0.001 = 0.198
    assert Decimal(tr["fee"]) == Decimal("0.19800000")


def test_portfolio_order_on_final_bar_warned_not_executed():
    ctx = PortfolioContext(MagicMock(), task_id=1, symbols=["AAA/USDT"])

    def on_bars(c):
        if c.bar("AAA/USDT") and c.bar("AAA/USDT").timestamp == "t0002":
            c.place_order(symbol="AAA/USDT", side="BUY", order_type="MARKET", amount="1")

    loop = PortfolioEventLoop(initial_capital=Decimal("100000"), symbols=["AAA/USDT"])
    section8 = loop.run(on_bars, ctx, {"AAA/USDT": _k(["100", "110", "120"])})
    assert section8["trades"] == []
    assert "末尾 bar 提交的 1 笔订单未参与撮合（回测区间已结束，NEXT_BAR 无下一根）" in section8["warnings"]


def test_portfolio_matching_config_passthrough_zero_slippage():
    """matching_config 消费证明:零滑点 → 成交价 = close 原价(与单标的同配置路径)。"""
    ctx = PortfolioContext(MagicMock(), task_id=1, symbols=["AAA/USDT"])

    def on_bars(c):
        if c.bar("AAA/USDT") and c.bar("AAA/USDT").timestamp == "t0000":
            c.place_order(symbol="AAA/USDT", side="BUY", order_type="MARKET", amount="1")

    loop = PortfolioEventLoop(
        initial_capital=Decimal("100000"), symbols=["AAA/USDT"], matching_config={"marketSlippageBps": "0"}
    )
    section8 = loop.run(on_bars, ctx, {"AAA/USDT": _k(["100", "110"])})
    assert Decimal(section8["trades"][0]["price"]) == Decimal("110.00000000")


# ---------------------------------------------------------------- 共享现金池闸门


def test_portfolio_shared_cash_pool_rejects_second_buy():
    """共享现金池:同步步两笔 BUY,第一笔占满现金 → 第二笔拒单(确定性 FIFO)。"""
    series = {
        "AAA/USDT": [
            {"timestamp": "t0000", "open": "60", "high": "60", "low": "60", "close": "60", "volume": "1"},
            {"timestamp": "t0001", "open": "60", "high": "60", "low": "60", "close": "60", "volume": "1"},
        ],
        "BBB/USDT": [
            {"timestamp": "t0000", "open": "60", "high": "60", "low": "60", "close": "60", "volume": "1"},
            {"timestamp": "t0001", "open": "60", "high": "60", "low": "60", "close": "60", "volume": "1"},
        ],
    }
    ctx = PortfolioContext(MagicMock(), task_id=1, symbols=["AAA/USDT", "BBB/USDT"])

    def on_bars(c):
        if c.bar("AAA/USDT") and c.bar("AAA/USDT").timestamp == "t0000":
            c.place_order(symbol="AAA/USDT", side="BUY", order_type="MARKET", amount="1")
            c.place_order(symbol="BBB/USDT", side="BUY", order_type="MARKET", amount="1")

    loop = PortfolioEventLoop(initial_capital=Decimal("100"), symbols=["AAA/USDT", "BBB/USDT"])
    section8 = loop.run(on_bars, ctx, series)

    # 60×1.0005=60.03 + fee 0.12006 ≈ 60.15;两笔需 ≈120.3 > 100 → 仅第一笔(队列首位)成交
    assert len(section8["trades"]) == 1
    assert section8["trades"][0]["symbol"] == "AAA/USDT"
    assert any("insufficient cash" in w for w in section8["warnings"])


def test_portfolio_sell_without_inventory_rejected():
    ctx = PortfolioContext(MagicMock(), task_id=1, symbols=["AAA/USDT"])

    def on_bars(c):
        c.place_order(symbol="AAA/USDT", side="SELL", order_type="MARKET", amount="1")

    loop = PortfolioEventLoop(symbols=["AAA/USDT"])
    section8 = loop.run(on_bars, ctx, {"AAA/USDT": _k(["100", "110"])})
    assert section8["trades"] == []
    assert any("insufficient inventory" in w for w in section8["warnings"])
    assert loop.position("AAA/USDT").qty == Decimal(0)


def test_portfolio_sell_dust_residual_clamped_to_full_close():
    """组合闸门同款 dust 容差(matching-spec §7,与单标的 event_loop 同构):
    SELL 超出持仓 < 1e-12 → clamp 全平不假拒。t0 BUY 1(t1 成交),t1 SELL 1+1e-15(t2 clamp 成交)。"""
    ctx = PortfolioContext(MagicMock(), task_id=1, symbols=["AAA/USDT"])
    state = {"stage": 0}

    def on_bars(c):
        if state["stage"] == 0:
            c.place_order(symbol="AAA/USDT", side="BUY", order_type="MARKET", amount="1")
            state["stage"] = 1
        elif state["stage"] == 1:
            c.place_order(symbol="AAA/USDT", side="SELL", order_type="MARKET",
                          amount=Decimal("1") + Decimal("1e-15"))
            state["stage"] = 2

    loop = PortfolioEventLoop(initial_capital=Decimal("10000"), symbols=["AAA/USDT"])
    section8 = loop.run(on_bars, ctx, {"AAA/USDT": _k(["100", "110", "120"])})

    assert not any("insufficient inventory" in w for w in section8["warnings"])
    assert len(section8["trades"]) == 2
    assert Decimal(section8["trades"][1]["amount"]) == Decimal("1")  # clamp 到账本原值
    assert loop.position("AAA/USDT").qty == Decimal(0)


# ---------------------------------------------------------------- 账本读取(逐位一致)


def test_portfolio_equity_and_cash_match_engine_ledger_bitwise():
    """验收核心:ctx.equity()/available_cash() 直接读引擎账本,与权益曲线逐位一致。

    场景:AAA t0 下单(t1 成交)→ BBB t1 下单(t2 成交);策略每步记录 equity/cash,
    与引擎输出的 equity_curve 及手工独立重算值精确相等(Decimal ==)。
    """
    ctx = PortfolioContext(MagicMock(), task_id=1, symbols=["AAA/USDT", "BBB/USDT"])
    seen_equity: list[Decimal] = []
    seen_cash: list[Decimal] = []

    def on_bars(c):
        seen_equity.append(c.equity())
        seen_cash.append(c.available_cash())
        b_a = c.bar("AAA/USDT")
        b_b = c.bar("BBB/USDT")
        if b_a and b_a.timestamp == "t0000":
            c.place_order(symbol="AAA/USDT", side="BUY", order_type="MARKET", amount="10")
        if b_b and b_b.timestamp == "t0001":
            c.place_order(symbol="BBB/USDT", side="BUY", order_type="MARKET", amount="20")

    loop = PortfolioEventLoop(initial_capital=Decimal("10000"), symbols=["AAA/USDT", "BBB/USDT"])
    section8 = loop.run(on_bars, ctx, _two_symbol_series())

    curve = [Decimal(p["equity"]) for p in section8["equity_curve"]]

    # 独立手工重算(不引用引擎内部):
    # AAA fill @t1: price=110×1.0005=110.055, qty=10 → 1100.55; fee=110.055×10×0.002=2.2011
    a_price = (Decimal("110") * Decimal("1.00050000")).quantize(Decimal("0.00000001"))
    a_fee = (a_price * Decimal("10") * Decimal("0.002")).quantize(Decimal("0.00000001"))
    cash_after_a = Decimal("10000") - a_price * Decimal("10") - a_fee
    # BBB fill @t2: price=60×1.0005=60.03, qty=20 → 1200.6; fee=60.03×20×0.002=2.4012
    b_price = (Decimal("60") * Decimal("1.00050000")).quantize(Decimal("0.00000001"))
    b_fee = (b_price * Decimal("20") * Decimal("0.002")).quantize(Decimal("0.00000001"))
    cash_after_b = cash_after_a - b_price * Decimal("20") - b_fee

    expected_equity = [
        Decimal("10000"),  # t0:无持仓
        cash_after_a + Decimal("10") * Decimal("110"),  # t1:AAA 持仓按 t1 close
        cash_after_b + Decimal("10") * Decimal("120") + Decimal("20") * Decimal("60"),  # t2
    ]
    expected_cash = [Decimal("10000"), cash_after_a, cash_after_b]

    assert seen_equity == expected_equity, "ctx.equity() 与独立重算不一致"
    assert seen_cash == expected_cash, "ctx.available_cash() 与独立重算不一致"
    # 与引擎输出的权益曲线逐位一致(策略所见 = 报告所记)
    assert seen_equity == curve

    # 终仓快照
    assert Decimal(section8["positions"]["AAA/USDT"]["qty"]) == Decimal("10")
    assert Decimal(section8["positions"]["AAA/USDT"]["avg_price"]) == a_price
    assert Decimal(section8["positions"]["BBB/USDT"]["qty"]) == Decimal("20")
    assert Decimal(section8["positions"]["BBB/USDT"]["avg_price"]) == b_price


def test_portfolio_position_qty_and_avg_price():
    ctx = PortfolioContext(MagicMock(), task_id=1, symbols=["AAA/USDT"])
    seen = []

    def on_bars(c):
        seen.append(c.position("AAA/USDT"))
        if c.bar("AAA/USDT") and c.bar("AAA/USDT").timestamp == "t0000":
            c.place_order(symbol="AAA/USDT", side="BUY", order_type="MARKET", amount="2")

    loop = PortfolioEventLoop(
        initial_capital=Decimal("100000"), symbols=["AAA/USDT"], matching_config={"marketSlippageBps": "0"}
    )
    loop.run(on_bars, ctx, {"AAA/USDT": _k(["100", "110", "120"])})
    # t0 下单 → t1 步开头撮合(先于 on_bars)→ t1/t2 步内均可见持仓
    assert seen[0].qty == Decimal(0)
    assert seen[1].qty == Decimal("2")
    assert seen[2].qty == Decimal("2")
    assert seen[2].avg_price == Decimal("110.00000000")


# ---------------------------------------------------------------- ctx 校验与容错


def test_portfolio_place_order_rejects_unknown_symbol():
    ctx = PortfolioContext(MagicMock(), task_id=1, symbols=["AAA/USDT"])
    with pytest.raises(ValueError, match="symbol 非法"):
        ctx.place_order(symbol="ZZZ/USDT", side="BUY", order_type="MARKET", amount="1")


def test_portfolio_position_returns_copy_not_live_ledger():
    """position() 必须返回副本:策略改写返回对象不能污染引擎账本,也不能架空 SELL 库存闸门。"""
    ctx = PortfolioContext(MagicMock(), task_id=1, symbols=["AAA/USDT"])
    loop = PortfolioEventLoop(initial_capital=Decimal("10000"), symbols=["AAA/USDT"])

    def on_bars(c):
        b = c.bar("AAA/USDT")
        if b and b.timestamp == "t0000":
            c.place_order(symbol="AAA/USDT", side="BUY", order_type="MARKET", amount="1")
        elif b and b.timestamp == "t0001":
            # 篡改 position 返回值 → 不应影响引擎账本
            p = c.position("AAA/USDT")
            p.qty = Decimal("999")
            # 若账本被污染,这笔超额卖会被放行;副本化后应被库存闸门拒
            c.place_order(symbol="AAA/USDT", side="SELL", order_type="MARKET", amount="999")

    section8 = loop.run(on_bars, ctx, {"AAA/USDT": _k(["100", "110", "120"])})

    # 账本未被污染:终仓仍为买入的 1 个
    assert Decimal(section8["positions"]["AAA/USDT"]["qty"]) == Decimal("1")
    # 超额卖被库存闸门拒(未产生负持仓/凭空现金)
    assert any("insufficient inventory" in w for w in section8["warnings"])
    # 仅 t1 那笔买入成交
    assert len(section8["trades"]) == 1
    assert section8["trades"][0]["side"] == "buy"


def test_portfolio_place_order_validation_fail_closed():
    ctx = PortfolioContext(MagicMock(), task_id=1, symbols=["AAA/USDT"])
    with pytest.raises(ValueError, match="side 非法"):
        ctx.place_order(symbol="AAA/USDT", side="HOLD", order_type="MARKET", amount="1")
    with pytest.raises(ValueError, match="order_type 非法"):
        ctx.place_order(symbol="AAA/USDT", side="BUY", order_type="FOK", amount="1")
    with pytest.raises(ValueError, match="amount 必须 > 0"):
        ctx.place_order(symbol="AAA/USDT", side="BUY", order_type="MARKET", amount="0")
    with pytest.raises(ValueError, match="price 必须 > 0"):
        ctx.place_order(symbol="AAA/USDT", side="BUY", order_type="LIMIT", amount="1", price="-1")


def test_portfolio_on_bars_exception_fails_closed():
    ctx = PortfolioContext(MagicMock(), task_id=1, symbols=["AAA/USDT"])

    def on_bars(c):
        raise RuntimeError("bug")

    loop = PortfolioEventLoop(symbols=["AAA/USDT"])
    with pytest.raises(RuntimeError, match="strategy on_bars failed.*bug"):
        loop.run(on_bars, ctx, {"AAA/USDT": _k(["100"])})


def test_portfolio_requires_portfolio_context():
    class BadCtx:
        pass

    with pytest.raises(TypeError):
        PortfolioEventLoop().run(lambda c: None, BadCtx(), {})  # type: ignore[arg-type]


def test_portfolio_symbols_accessor_and_empty_defaults():
    ctx = PortfolioContext(MagicMock(), task_id=1, symbols=["AAA/USDT", "BBB/USDT"])
    assert ctx.symbols() == ["AAA/USDT", "BBB/USDT"]
    # 未绑定引擎前的安全默认(不抛)
    assert ctx.equity() == Decimal(0)
    assert ctx.available_cash() == Decimal(0)
    assert ctx.position("AAA/USDT").qty == Decimal(0)
    assert ctx.bar("AAA/USDT") is None
    assert ctx.history("close", 5, symbol="AAA/USDT") == []
    ctx.cancel(1)  # no-op
    assert ctx.symbol == ""


def test_portfolio_progress_report_throttled():
    client = MagicMock()
    ctx = PortfolioContext(client, task_id=7, symbols=["AAA/USDT"])
    loop = PortfolioEventLoop(symbols=["AAA/USDT"])
    loop.run(lambda c: None, ctx, {"AAA/USDT": _k(["1", "2", "3"])})
    assert client.trade.report_progress.call_count == 1
    assert client.trade.report_progress.call_args == call(7, 3, 3)


def test_portfolio_progress_failure_does_not_break():
    client = MagicMock()
    client.trade.report_progress.side_effect = RuntimeError("network down")
    ctx = PortfolioContext(client, task_id=1, symbols=["AAA/USDT"])
    loop = PortfolioEventLoop(symbols=["AAA/USDT"])
    section8 = loop.run(lambda c: None, ctx, {"AAA/USDT": _k(["1", "2"])})
    assert len(section8["equity_curve"]) == 2


# ---------------------------------------------------------------- 轮动策略冒烟


def test_portfolio_rotation_smoke_three_symbols():
    """验收用例同构冒烟:3 标的动量轮动——每步比较动量,持有最强者,切换时卖旧买新。

    数据:AAA 前段最强(涨),中段 BBB 最强,后段 CCC 最强 → 应发生切换交易,
    输出组合权益曲线 + 分标的成交(symbol 标记)+ 终仓快照。
    """
    # 3 段 × 4 根:每段轮换一个标的领涨
    aaa = ["100", "102", "104", "106", "106", "104", "102", "100", "100", "99", "98", "97"]
    bbb = ["50", "50", "49", "49", "50", "52", "54", "56", "56", "55", "54", "53"]
    ccc = ["10", "10", "10", "10", "10", "10", "10", "10", "11", "12", "13", "14"]
    series = {"AAA/USDT": _k(aaa), "BBB/USDT": _k(bbb), "CCC/USDT": _k(ccc)}
    syms = list(series.keys())

    def momentum(c, s: str) -> float | None:
        closes = c.history("close", 4, symbol=s)
        if len(closes) < 4 or closes[0] <= 0:
            return None
        return closes[-1] / closes[0] - 1.0

    def on_bars(c):
        scores = {s: momentum(c, s) for s in c.symbols()}
        valid = {s: m for s, m in scores.items() if m is not None}
        if not valid:
            return
        target = max(valid, key=lambda s: valid[s])
        holding = [s for s in c.symbols() if c.position(s).qty > 0]
        if target in holding:
            return
        for s in holding:  # 卖出非目标持仓(close_position 用账本原值,零残差)
            c.close_position(symbol=s)
        # 用可用现金买入目标(估算价预留 20% 余量:NEXT_BAR 成交在下一根,强势标的可能跳空,
        # 估低了会因共享现金池闸门拒单;真实策略同样要留余量)。
        # 金额全程 Decimal(金额红线:float 残差在 place_order 入口拒)
        closes = c.history("close", 1, symbol=target)
        if closes:
            est_price = Decimal(str(closes[-1])) * Decimal("1.2")
            if est_price > 0:
                qty = round(c.available_cash() / est_price, 8)
                if qty > 0:
                    c.place_order(symbol=target, side="BUY", order_type="MARKET", amount=qty)

    ctx = PortfolioContext(MagicMock(), task_id=1, symbols=syms)
    loop = PortfolioEventLoop(initial_capital=Decimal("10000"), symbols=syms, timeframe="1h")
    section8 = loop.run(on_bars, ctx, series)

    assert len(section8["equity_curve"]) == 12
    assert len(section8["trades"]) >= 3, f"轮动应产生多次切换交易: {section8['trades']}"
    traded_symbols = {t["symbol"] for t in section8["trades"]}
    assert traded_symbols >= {"AAA/USDT", "BBB/USDT", "CCC/USDT"}, traded_symbols
    # 终仓:末段 CCC 最强 → 持有 CCC
    assert Decimal(section8["positions"]["CCC/USDT"]["qty"]) > 0
    # 每笔成交都带 symbol 标记
    assert all(t["symbol"] in syms for t in section8["trades"])
    # 权益为正(现金池未被记坏)
    assert Decimal(section8["equity_curve"][-1]["equity"]) > 0
