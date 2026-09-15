"""组合(多标的)PERP 账户账本 + 引擎测试(docs/perp-backtest-spec.md §10)。

核心锁:
- **N=1 差分**:单标的 PerpLedger 与组合 PerpPortfolioLedger(1 标的)逐操作(gate/apply/funding/
  强平)结果逐字节一致——组合是单标的的账户级泛化,不得漂移(spec §10 承诺)。
- **Model B 对冲安全**:长 A + 短 B 组合,单腿脉冲不误强平健康对冲(Model C 会,§10.4)。
- **CROSS 账户级聚合**:穿仓判定用 free_backing + Σ unrealized vs Σ maint(§10.2)。
"""

from __future__ import annotations

from datetime import datetime, timezone
from decimal import Decimal

from kwikquant_worker.backtest.perp_ledger import (
    FundingPeriod,
    PerpLedger,
    PerpPortfolioLedger,
)

SYM = "BTC/USDT:USDT"
FEE = Decimal("0")  # fee=0 简化手算;fee 语义由内核/单标的测试覆盖


def _apply_both(single: PerpLedger, port: PerpPortfolioLedger, sym, **kw):
    """对单标的与组合账本施加同一笔成交(gate 先对拍再 apply),返回两侧 gate 结果。"""
    g1 = single.gate(**kw)
    g2 = port.gate(sym, **kw)
    assert g1 == g2, f"gate 分叉: single={g1!r} portfolio={g2!r}"
    if g1 is None:
        single.apply_fill(**kw)
        port.apply_fill(sym, **kw)
    return g1


def _assert_state_eq(single: PerpLedger, port: PerpPortfolioLedger, sym, close):
    assert single.cash == port.cash, f"cash 分叉 {single.cash} vs {port.cash}"
    p = port.position(sym)
    assert single.pos.signed_qty == p.signed_qty
    assert single.pos.avg_price == p.avg_price
    assert single.pos.margin == p.margin
    assert single.realized_pnl == port.realized_pnl
    assert single.equity(close) == port.equity({sym: close})
    assert single.available() == port.available()


def _ledgers(cap="100000", mmr=None):
    single = PerpLedger(initial_capital=Decimal(cap), taker_fee_rate=FEE, maint_margin_rate=mmr)
    port = PerpPortfolioLedger(initial_capital=Decimal(cap), taker_fee_rate=FEE, maint_margin_rate=mmr)
    return single, port


def test_n1_isolated_open_add_partial_close_matches_single():
    """N=1 ISOLATED:开→加→部分平→全平,组合账本与单标的逐操作一致。"""
    single, port = _ledgers()
    steps = [
        dict(position_effect="OPEN_LONG", fill_qty=Decimal("1"), fill_price=Decimal("100"), fee=FEE, leverage=10, margin_mode="ISOLATED"),
        dict(position_effect="OPEN_LONG", fill_qty=Decimal("1"), fill_price=Decimal("120"), fee=FEE, leverage=None, margin_mode=None),
        dict(position_effect="CLOSE_LONG", fill_qty=Decimal("0.5"), fill_price=Decimal("130"), fee=FEE, leverage=None, margin_mode=None),
        dict(position_effect="CLOSE_LONG", fill_qty=Decimal("1.5"), fill_price=Decimal("90"), fee=FEE, leverage=None, margin_mode=None),
    ]
    for i, kw in enumerate(steps):
        _apply_both(single, port, SYM, **kw)
        _assert_state_eq(single, port, SYM, close=Decimal("110"))
    assert single.is_flat() and port.is_flat(SYM)


def test_n1_flip_through_zero_matches_single():
    """N=1:穿零反转(CLOSE 段 + OPEN 段)组合账本与单标的一致。"""
    single, port = _ledgers()
    _apply_both(single, port, SYM, position_effect="OPEN_LONG", fill_qty=Decimal("2"), fill_price=Decimal("100"), fee=FEE, leverage=5, margin_mode="ISOLATED")
    _apply_both(single, port, SYM, position_effect="OPEN_SHORT", fill_qty=Decimal("3"), fill_price=Decimal("110"), fee=FEE, leverage=None, margin_mode=None)
    _assert_state_eq(single, port, SYM, close=Decimal("105"))
    assert port.position(SYM).signed_qty == Decimal("-1")


def test_n1_cross_liquidation_matches_single():
    """N=1 CROSS 强平:同一 bar 极值下组合账户级 Model B 与单标的 check_liquidation 逐字一致
    (退化性质:free_backing=cash、gap→open、adverse→极值、渐进清算=全平)。"""
    single, port = _ledgers(cap="1000")
    # 10x CROSS 多头,名义 10000 由 1000 现金担保(账户 10x):大跌击穿账户
    kw = dict(position_effect="OPEN_LONG", fill_qty=Decimal("10"), fill_price=Decimal("1000"), fee=FEE, leverage=10, margin_mode="CROSS")
    _apply_both(single, port, SYM, **kw)
    # 大跌 bar:low 击穿(cash 1000 + unrealized(low) vs maint)
    liq_single = single.check_liquidation(timestamp="t1", open_=Decimal("990"), high=Decimal("995"), low=Decimal("100"))
    recs = port.liquidate("t1", {SYM: (Decimal("990"), Decimal("995"), Decimal("100"), Decimal("500"))})
    assert liq_single is not None
    assert len(recs) == 1
    _sym, rec = recs[0]
    assert rec.price == liq_single.price
    assert rec.qty == liq_single.qty
    assert rec.gross_pnl == liq_single.gross_pnl
    assert single.cash == port.cash
    assert port.is_flat(SYM)


def test_cross_account_aggregation_two_symbols():
    """两个 CROSS 仓:账户穿仓用 free_backing + Σ unrealized vs Σ maint(§10.2),非逐仓。"""
    port = PerpPortfolioLedger(initial_capital=Decimal("2000"), taker_fee_rate=FEE)
    a, b = "AAA/USDT:USDT", "BBB/USDT:USDT"
    # 各 10000 名义由 2000 现金担保(账户 10x)
    port.apply_fill(a, position_effect="OPEN_LONG", fill_qty=Decimal("10"), fill_price=Decimal("1000"), fee=FEE, leverage=10, margin_mode="CROSS")
    port.apply_fill(b, position_effect="OPEN_LONG", fill_qty=Decimal("10"), fill_price=Decimal("1000"), fee=FEE, leverage=10, margin_mode="CROSS")
    # 两仓都小跌:各自 close 下账户仍健康(free_backing=2000,Σunrealized 小负,Σmaint 小)
    recs = port.liquidate("t", {a: (None, None, None, Decimal("980")), b: (None, None, None, Decimal("980"))})
    assert recs == []
    # A 深跌到极值:单腿脉冲情景令账户穿仓 → 全平所有 CROSS 仓(symbol 升序,A 在前)
    recs = port.liquidate("t2", {a: (Decimal("980"), Decimal("985"), Decimal("100"), Decimal("980")), b: (None, None, None, Decimal("980"))})
    assert len(recs) == 2  # 账户穿仓即全平所有 CROSS 仓
    assert recs[0][0] == a  # symbol 升序


def test_model_b_hedge_not_spuriously_liquidated():
    """Model B 对冲安全:长 A + 短 B,A 见 low 的同 bar B 见 high 物理不可能同时发生。
    单腿脉冲情景(A 至 low、B 留 close)不把健康对冲误强平——Model C(两腿同取逆向极值)会。"""
    port = PerpPortfolioLedger(initial_capital=Decimal("3000"), taker_fee_rate=FEE)
    a, b = "AAA/USDT:USDT", "BBB/USDT:USDT"
    # 对冲:A 多 / B 空,各 1000 名义 5x CROSS
    port.apply_fill(a, position_effect="OPEN_LONG", fill_qty=Decimal("1"), fill_price=Decimal("1000"), fee=FEE, leverage=5, margin_mode="CROSS")
    port.apply_fill(b, position_effect="OPEN_SHORT", fill_qty=Decimal("1"), fill_price=Decimal("1000"), fee=FEE, leverage=5, margin_mode="CROSS")
    # 同 bar:A 从 1000 波动 [960,1005] close 970(小亏),B [995,1040] close 1030(空头小亏)。
    # 单腿脉冲:A 至 960(亏 40)其余 close;B 至 1040(空头亏 40)其余 close。free_backing 3000 充裕 → 不强平。
    recs = port.liquidate("t", {
        a: (Decimal("1000"), Decimal("1005"), Decimal("960"), Decimal("970")),
        b: (Decimal("1000"), Decimal("1040"), Decimal("995"), Decimal("1030")),
    })
    assert recs == [], "健康对冲被误强平(Model B 不应假设两腿同时逆向)"


def test_isolated_legs_independent_liquidation():
    """两个 ISOLATED 仓:一腿穿仓强平,另一腿(保证金充足)不受影响(仓位级解耦)。"""
    port = PerpPortfolioLedger(initial_capital=Decimal("10000"), taker_fee_rate=FEE)
    a, b = "AAA/USDT:USDT", "BBB/USDT:USDT"
    port.apply_fill(a, position_effect="OPEN_LONG", fill_qty=Decimal("1"), fill_price=Decimal("1000"), fee=FEE, leverage=50, margin_mode="ISOLATED")  # 薄保证金
    port.apply_fill(b, position_effect="OPEN_LONG", fill_qty=Decimal("1"), fill_price=Decimal("1000"), fee=FEE, leverage=2, margin_mode="ISOLATED")  # 厚保证金
    recs = port.liquidate("t", {
        a: (Decimal("990"), Decimal("995"), Decimal("900"), Decimal("950")),  # A 大跌击穿薄保证金
        b: (Decimal("990"), Decimal("995"), Decimal("960"), Decimal("970")),  # B 小跌,厚保证金扛住
    })
    liq_syms = {s for s, _ in recs}
    assert a in liq_syms
    assert b not in liq_syms
    assert port.is_flat(a)
    assert not port.is_flat(b)


def test_funding_per_symbol_settles_independently():
    """per-symbol 资金费:各标的按自身持仓方向/量结算,cash 与 funding_cum 账户级累加。"""
    port = PerpPortfolioLedger(initial_capital=Decimal("10000"), taker_fee_rate=FEE)
    a, b = "AAA/USDT:USDT", "BBB/USDT:USDT"
    port.apply_fill(a, position_effect="OPEN_LONG", fill_qty=Decimal("2"), fill_price=Decimal("1000"), fee=FEE, leverage=5, margin_mode="CROSS")
    port.apply_fill(b, position_effect="OPEN_SHORT", fill_qty=Decimal("1"), fill_price=Decimal("1000"), fee=FEE, leverage=5, margin_mode="CROSS")
    fa = port.settle_funding_period(a, Decimal("0.0001"), Decimal("1000"))  # 多头付:负
    fb = port.settle_funding_period(b, Decimal("0.0001"), Decimal("1000"))  # 空头收:正
    assert fa < 0 and fb > 0
    assert port.funding_cum == fa + fb
    # flat 标的不结算
    c = "CCC/USDT:USDT"
    assert port.settle_funding_period(c, Decimal("0.0001"), Decimal("1000")) == Decimal("0")


def test_close_position_effect_derivation_via_engine_ctx():
    """组合 ctx PERP close_position 按 signed 派生 CLOSE_LONG/CLOSE_SHORT,全平量 = |signed_qty|。"""
    from unittest.mock import MagicMock

    from kwikquant_worker.portfolio import PortfolioContext, PortfolioEventLoop

    loop = PortfolioEventLoop(initial_capital=Decimal("10000"), symbols=[SYM], timeframe="1h", market_type="PERP")
    loop._perp = PerpPortfolioLedger(initial_capital=Decimal("10000"), taker_fee_rate=FEE)
    loop._perp.apply_fill(SYM, position_effect="OPEN_SHORT", fill_qty=Decimal("3"), fill_price=Decimal("1000"), fee=FEE, leverage=5, margin_mode="CROSS")
    ctx = PortfolioContext(MagicMock(), 1, market_type="PERP", symbols=[SYM])
    ctx.bind(loop)
    ack = ctx.close_position(SYM)
    assert ack.accepted
    intents = ctx.take_pending()
    assert len(intents) == 1
    assert intents[0].position_effect == "CLOSE_SHORT"
    assert intents[0].amount == Decimal("3")


def _kline(ts, o, h, l, c):
    return {"timestamp": ts, "open": o, "high": h, "low": l, "close": c, "volume": "1"}


def test_perp_portfolio_engine_end_to_end_section8():
    """引擎端到端:PERP 组合跑一个简单策略,section8 带 market_type/liquidation_model/
    signed positions/equity_curve margin_used+funding_cum/trade position_effect。"""
    from kwikquant_worker.portfolio import PortfolioContext, PortfolioEventLoop

    a = "AAA/USDT:USDT"
    series = {
        a: [
            _kline("2026-01-01T00:00:00Z", "1000", "1010", "990", "1000"),
            _kline("2026-01-01T01:00:00Z", "1000", "1020", "1000", "1010"),
            _kline("2026-01-01T02:00:00Z", "1010", "1030", "1005", "1025"),
        ]
    }

    def on_bars(ctx):
        pos = ctx.position(a)
        if pos.qty == 0 and ctx.bar(a) is not None:
            ctx.place_order(symbol=a, order_type="MARKET", amount="1", position_effect="OPEN_LONG", leverage=5, margin_mode="ISOLATED")

    loop = PortfolioEventLoop(
        initial_capital=Decimal("100000"),
        symbols=[a],
        timeframe="1h",
        market_type="PERP",
        funding_periods={a: []},
        task_end="2026-01-01T02:00:00Z",
        pair_specs={a: {"symbol": a, "marketType": "PERP", "minQty": "0.001", "maxQty": None,
                        "tickSize": "0.1", "stepSize": "0.001", "maxLeverage": 100}},
    )
    ctx = PortfolioContext(loop._client if hasattr(loop, "_client") else __import__("unittest.mock", fromlist=["MagicMock"]).MagicMock(), 1, market_type="PERP", symbols=[a])
    out = loop.run(on_bars, ctx, series)
    assert out["market_type"] == "PERP"
    assert out["liquidation_model"] == "BAR_EXTREME_APPROX"
    assert out["symbols"] == [a]
    # 开多一笔(NEXT_BAR:bar0 下单 bar1 成交)
    opens = [t for t in out["trades"] if t["position_effect"] == "OPEN_LONG"]
    assert len(opens) == 1 and opens[0]["symbol"] == a
    # equity_curve 带 PERP 列
    assert all("margin_used" in e and "funding_cum" in e for e in out["equity_curve"])
    # 终仓 signed 净持仓
    assert a in out["positions"]
    assert Decimal(out["positions"][a]["qty"]) == Decimal("1")
    assert out["positions"][a]["margin_mode"] == "ISOLATED"


def test_n1_engine_section8_fields_byte_identical():
    """§10.9 红线的引擎级守护:同一单标的 PERP 策略经单标的引擎与组合引擎(1 标的)跑完,
    trades/equity_curve 的**数值字段序列化逐字节一致**(section8 外壳因组合封装 symbol/positions
    形态不同,不比外壳)。**零 fee 场景**专门守护 norm 纪律——不过 norm 则组合侧 fee="0.00000000"、
    单标的侧 "0",字节分叉(架构师 P1-1)。"""
    from unittest.mock import MagicMock

    from kwikquant_worker.event_loop import BacktestEventLoop
    from kwikquant_worker.portfolio import PortfolioContext, PortfolioEventLoop
    from kwikquant_worker.strategy import BacktestContext

    sym = "BTC/USDT"
    pair_specs = {
        sym: {
            "symbol": sym, "marketType": "PERP", "minQty": "0.001", "maxQty": None,
            "tickSize": "0.1", "stepSize": "0.001", "maxLeverage": 100,
        }
    }
    mcfg = {"takerFeeRate": "0", "marketSlippageBps": "0"}  # 零 fee:精确触发未 norm 时的字节分叉
    klines = [
        {"timestamp": f"2024-01-01T0{i}:00:00Z", "open": p, "high": p, "low": p, "close": p, "volume": "10"}
        for i, p in enumerate(["40000", "41000", "42000", "43000"])
    ]

    def make_single():
        st = {"n": 0}

        def on_bar(bar, ctx):
            st["n"] += 1
            if st["n"] == 1:
                ctx.place_order(order_type="MARKET", amount="0.1", position_effect="OPEN_LONG", leverage=10, margin_mode="ISOLATED")
            elif st["n"] == 3 and ctx.position().qty != 0:
                ctx.close_position()

        return on_bar

    def make_portfolio():
        st = {"n": 0}

        def on_bars(ctx):
            st["n"] += 1
            if st["n"] == 1:
                ctx.place_order(symbol=sym, order_type="MARKET", amount="0.1", position_effect="OPEN_LONG", leverage=10, margin_mode="ISOLATED")
            elif st["n"] == 3 and ctx.position(sym).qty != 0:
                ctx.close_position(sym)

        return on_bars

    single = BacktestEventLoop(
        initial_capital=Decimal("100000"), symbol=sym, timeframe="1h", market_type="PERP",
        pair_specs=pair_specs, funding_periods=[], matching_config=mcfg, task_end="2024-01-01T03:00:00Z",
    ).run(make_single(), BacktestContext(MagicMock(), 1, market_type="PERP", symbol=sym), klines)

    port = PortfolioEventLoop(
        initial_capital=Decimal("100000"), symbols=[sym], timeframe="1h", market_type="PERP",
        funding_periods={sym: []}, task_end="2024-01-01T03:00:00Z", matching_config=mcfg, pair_specs=pair_specs,
    ).run(make_portfolio(), PortfolioContext(MagicMock(), 1, market_type="PERP", symbols=[sym]), {sym: klines})

    # 成交对拍(数值字段逐字节;组合额外的 symbol 字段不参与,外壳形态本就不同)
    def tk(t):
        return (t["time"], t["side"], t["price"], t["amount"], t["fee"], t.get("position_effect"), t.get("liquidation", False))

    assert [tk(t) for t in single["trades"]] == [tk(t) for t in port["trades"]]
    assert len(single["trades"]) == 2  # 开 + 平,确保真跑出成交(非空对拍)

    # 权益曲线数值字段逐字节
    def ek(e):
        return (e["time"], e["equity"], e.get("margin_used"), e.get("funding_cum"))

    assert [ek(e) for e in single["equity_curve"]] == [ek(e) for e in port["equity_curve"]]
    # 零 fee 经 norm 规范化为 "0"(非 "0.00000000")——两侧一致
    assert all(t["fee"] == "0" for t in port["trades"])


def test_portfolio_perp_leverage_out_of_range_rejected_not_crash():
    """组合 PERP leverage 越界经 acceptance 拒单进 warnings,不再崩溃整任务(§10.6):
    下限 0 曾让 gate_position→initial_margin 抛 ValueError 逃逸→"event loop failed" exit 1(不透明);
    上限 >maxLeverage 曾静默受理产出不真实报告(单标的 acceptance 会拒)。两者现均 LEVERAGE 拒单。"""
    from unittest.mock import MagicMock

    from kwikquant_worker.portfolio import PortfolioContext, PortfolioEventLoop

    a = "AAA/USDT:USDT"
    series = {a: [_kline(f"2026-01-01T0{i}:00:00Z", "1000", "1000", "1000", "1000") for i in range(3)]}
    specs = {a: {"symbol": a, "marketType": "PERP", "minQty": "0.001", "maxQty": None,
                 "tickSize": "0.1", "stepSize": "0.001", "maxLeverage": 100}}

    for bad_lev in (0, 500):  # 下限违规(曾崩溃) / 上限越界(曾静默受理)

        def on_bars(ctx, _lev=bad_lev):
            if ctx.position(a).qty == 0 and ctx.bar(a) is not None:
                ctx.place_order(symbol=a, order_type="MARKET", amount="1", position_effect="OPEN_LONG", leverage=_lev, margin_mode="ISOLATED")

        loop = PortfolioEventLoop(
            initial_capital=Decimal("100000"), symbols=[a], timeframe="1h", market_type="PERP",
            funding_periods={a: []}, pair_specs=specs,
        )
        out = loop.run(on_bars, PortfolioContext(MagicMock(), 1, market_type="PERP", symbols=[a]), series)  # 不崩溃
        assert out["trades"] == [], f"lev={bad_lev} 不应成交"
        assert out["positions"] == {}
        assert any("rejected" in w and "LEVERAGE" in w for w in out["warnings"]), f"lev={bad_lev} 应有 LEVERAGE 拒单 warning"


def test_portfolio_perp_cross_liquidation_emits_section8_trade_row():
    """组合 CROSS 强平经引擎跑出 section8 强平 trade 行(架构师 P2-3 覆盖缺口):
    liquidation=True、position_effect=CLOSE_LONG、side 与被平方向反向(LONG→sell)、带 symbol。"""
    from unittest.mock import MagicMock

    from kwikquant_worker.portfolio import PortfolioContext, PortfolioEventLoop

    sym = "BTC/USDT"
    specs = {sym: {"symbol": sym, "marketType": "PERP", "minQty": "0.001", "maxQty": None,
                   "tickSize": "0.1", "stepSize": "0.001", "maxLeverage": 100}}
    mcfg = {"takerFeeRate": "0", "marketSlippageBps": "0"}  # 零 fee 让 10x 满仓开仓过现金闸门
    series = {sym: [
        _kline("2024-01-01T00:00:00Z", "1000", "1000", "1000", "1000"),
        _kline("2024-01-01T01:00:00Z", "1000", "1000", "1000", "1000"),
        _kline("2024-01-01T02:00:00Z", "1000", "1000", "100", "500"),  # 插针 low=100 → 账户穿仓
        _kline("2024-01-01T03:00:00Z", "500", "500", "500", "500"),
    ]}

    def make():
        st = {"n": 0}

        def on_bars(ctx):
            st["n"] += 1
            if st["n"] == 1:
                ctx.place_order(symbol=sym, order_type="MARKET", amount="10", position_effect="OPEN_LONG", leverage=10, margin_mode="CROSS")

        return on_bars

    loop = PortfolioEventLoop(
        initial_capital=Decimal("1000"), symbols=[sym], timeframe="1h", market_type="PERP",
        funding_periods={sym: []}, matching_config=mcfg, pair_specs=specs,
    )
    out = loop.run(make(), PortfolioContext(MagicMock(), 1, market_type="PERP", symbols=[sym]), series)

    liq = [t for t in out["trades"] if t.get("liquidation")]
    assert len(liq) == 1
    assert liq[0]["position_effect"] == "CLOSE_LONG"
    assert liq[0]["side"] == "sell"  # LONG 被平 → sell
    assert liq[0]["symbol"] == sym
    assert any("强平" in w for w in out["warnings"])
    assert out["positions"] == {}  # 强平后 flat,positions 过滤空仓
