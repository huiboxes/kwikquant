"""事件时间轴与策略事件回调差分锁死。

规范:docs/perp-backtest-spec.md §5/§6(BAR/FUNDING 节点归并、mark 真值化、派发点)、
docs/strategy-api.md §8(三回调契约、派发时序矩阵、回调内下单、异常语义)、
docs/ws-contract.md §5(runner 按需订阅、symbol 过滤)。

本文件锁死的声明:
1. 回测 BAR 节点内**同步有序**派发:on_liquidation → on_fill → on_bar → on_funding;
   强平不双派 on_fill(与 runner /topic/liquidations 与 /topic/fills 通道互斥对齐);
2. 资金费 mark 真值化:期次行 mark_price 非空用交易所真值,null fallback 归属 bar close
   (旧口径数值);payload.funding_time = 精确时间戳(可落 bar 中段);
3. equity 口径不变:本 bar 归属期次在本 bar equity 点即反映(§5.2);flat 期次不结算不派发;
4. 回调内 place_order 同一意图队列 NEXT_BAR;回调异常 fail-fast 转 RuntimeError;
5. 回调派发前账本已落账:回调内 ctx.position() 已含本笔成交/强平;
6. runner WS 载荷转换(金额 number → Decimal(str) 防御)/symbol 过滤/按需订阅/容错不杀进程。

期望值手算(PERP:MARKET fill = 42000×1.0005 = 42021,taker 0.002,lev 10 ISOLATED;
SPOT:100×1.0005 = 100.05)。账本数学本身由 test_perp_ledger.py 锁定,本文件锁**编排与派发**。
"""

from __future__ import annotations

import asyncio
from decimal import ROUND_HALF_UP, Decimal
from unittest.mock import MagicMock

import pytest

from kwikquant_worker.backtest.perp_ledger import FundingPeriod, parse_instant
from kwikquant_worker.context import FillEvent, FundingEvent, LiquidationEvent
from kwikquant_worker.event_loop import (
    BacktestEventLoop,
    RunnerEventLoop,
    _fill_event_from_ws,
    _funding_event_from_ws,
    _liquidation_event_from_ws,
)
from kwikquant_worker.portfolio import PortfolioContext, PortfolioEventLoop
from kwikquant_worker.strategy import BacktestContext

D = Decimal

PAIR_SPECS = {
    "BTC/USDT": {
        "symbol": "BTC/USDT",
        "marketType": "PERP",
        "minQty": "0.001",
        "maxQty": None,
        "tickSize": "0.1",
        "stepSize": "0.001",
        "maxLeverage": 100,
    }
}

FILL_PX = D("42021.00000000")  # 42000 × 1.00050000
LIQ_REF = D("38008.94472362")  # (4202.1 − 420.21) / (0.1 × 0.995)


def _klines(rows: list[tuple[str, str, str, str, str]]):
    return [
        {"timestamp": ts, "open": o, "high": h, "low": lo, "close": c, "volume": "10"}
        for ts, o, h, lo, c in rows
    ]


def _flat_klines(n: int, px: str = "42000"):
    return _klines([(f"2024-01-01T0{i}:00:00Z", px, px, px, px) for i in range(n)])


def _perp_ctx() -> BacktestContext:
    return BacktestContext(MagicMock(), task_id=1, market_type="PERP", symbol="BTC/USDT")


def _perp_loop(funding=None) -> BacktestEventLoop:
    return BacktestEventLoop(
        initial_capital=D("100000"),
        symbol="BTC/USDT",
        timeframe="1h",
        market_type="PERP",
        pair_specs=PAIR_SPECS,
        funding_periods=[] if funding is None else funding,
    )


def _open_long_once(seq: list, name: str = "bar"):
    """构造 on_bar:首根下 OPEN_LONG 0.1 lev10 ISOLATED,记录调用序列。"""
    state = {"placed": False}

    def on_bar(bar, ctx):
        seq.append((name, bar.timestamp))
        if not state["placed"]:
            ctx.place_order(
                position_effect="OPEN_LONG", order_type="MARKET", amount=D("0.1"),
                leverage=10, margin_mode="ISOLATED",
            )
            state["placed"] = True

    return on_bar


# ---------------------------------------------------------------- 派发顺序(回测同步有序)


class TestBacktestDispatchOrder:
    def test_perp_fill_before_on_bar_funding_after(self):
        """BAR 节点内顺序:撮合派发 on_fill → on_bar → FUNDING 节点派发 on_funding。

        期次 T=02:00 ∈ (01:00, 02:00] 归 bar1(左开右闭);bar0 下单 → bar1 成交。"""
        periods = [
            FundingPeriod(funding_time=parse_instant("2024-01-01T02:00:00Z"), settled_rate=D("0.0001"),
                          interval_seconds=3600, mark_price=None),
        ]
        seq: list = []
        on_bar = _open_long_once(seq)

        def on_fill(fill, ctx):
            seq.append(("fill", fill.filled_at, fill.position_effect))

        def on_funding(ev, ctx):
            seq.append(("funding", ev.funding_time))

        _perp_loop(funding=periods).run(
            on_bar, _perp_ctx(), _flat_klines(4), on_fill=on_fill, on_funding=on_funding
        )
        assert seq == [
            ("bar", "2024-01-01T00:00:00Z"),
            ("fill", "2024-01-01T01:00:00Z", "OPEN_LONG"),
            ("bar", "2024-01-01T01:00:00Z"),
            ("funding", "2024-01-01T02:00:00Z"),
            ("bar", "2024-01-01T02:00:00Z"),
            ("bar", "2024-01-01T03:00:00Z"),
        ]

    def test_liquidation_before_on_bar_and_no_double_fill(self):
        """强平派发 on_liquidation(先于本 bar on_bar),且不双派 on_fill(通道互斥)。"""
        rows = [
            ("2024-01-01T00:00:00Z", "42000", "42000", "42000", "42000"),
            ("2024-01-01T01:00:00Z", "42000", "42000", "42000", "42000"),
            ("2024-01-01T02:00:00Z", "42000", "42000", "38000", "38500"),
        ]
        seq: list = []
        liq_seen: list = []
        on_bar = _open_long_once(seq)

        def on_fill(fill, ctx):
            seq.append(("fill", fill.filled_at))

        def on_liquidation(ev, ctx):
            seq.append(("liq", ev.timestamp))
            liq_seen.append((ev, ctx.position().qty))

        _perp_loop().run(
            on_bar, _perp_ctx(), _klines(rows), on_fill=on_fill, on_liquidation=on_liquidation
        )
        assert seq == [
            ("bar", "2024-01-01T00:00:00Z"),
            ("fill", "2024-01-01T01:00:00Z"),
            ("bar", "2024-01-01T01:00:00Z"),
            ("liq", "2024-01-01T02:00:00Z"),
            ("bar", "2024-01-01T02:00:00Z"),
        ]
        # 强平不双派 on_fill:全程只有开仓一笔 fill 事件
        assert sum(1 for s in seq if s[0] == "fill") == 1

        ev, pos_qty = liq_seen[0]
        assert ev.symbol == "BTC/USDT"
        assert ev.position_side == "LONG"
        assert ev.qty == D("0.1")
        assert ev.price == LIQ_REF  # bar 极值近似:open 未破 → ISOLATED 参考价
        assert ev.margin_mode == "ISOLATED"
        assert ev.reason is None  # 回测恒 None(近似模型声明在报告层)
        # realized_pnl = 净额(毛 − 强平 fee),毛 = (LIQ_REF − FILL_PX) × 0.1
        gross = (LIQ_REF - FILL_PX) * D("0.1")
        fee = (LIQ_REF * D("0.1") * D("0.002")).quantize(D("0.00000001"), rounding=ROUND_HALF_UP)
        assert ev.realized_pnl == gross - fee
        assert pos_qty == 0  # 回调内 position() 已是强平后 flat 态(派发前账本已落账)

    def test_on_liquidation_order_fills_next_bar_not_same(self):
        """NEXT_BAR 结构保证(spec §6):意图队列在 BAR 节点开头 drain(强平段之前),
        on_liquidation 内下的单落**下一根** bar——若 drain 晚于强平段,回调单会被本 bar
        撮合(强平时点策略尚未"看到"本 bar OHLC,同 bar 成交 = 前视偏差)。"""
        rows = [
            ("2024-01-01T00:00:00Z", "42000", "42000", "42000", "42000"),
            ("2024-01-01T01:00:00Z", "42000", "42000", "42000", "42000"),
            ("2024-01-01T02:00:00Z", "42000", "42000", "38000", "38500"),
            ("2024-01-01T03:00:00Z", "38500", "38500", "38500", "38500"),
        ]
        on_bar = _open_long_once([])

        def on_liquidation(ev, ctx):
            # 强平事件内重新开仓:意图必须留到 bar3 撮合
            ctx.place_order(
                position_effect="OPEN_LONG", order_type="MARKET", amount=D("0.1"),
                leverage=10, margin_mode="ISOLATED",
            )

        out = _perp_loop().run(on_bar, _perp_ctx(), _klines(rows), on_liquidation=on_liquidation)
        trades = out["trades"]
        assert [t["time"] for t in trades] == [
            "2024-01-01T01:00:00Z",  # 开仓(bar0 下单 → bar1 撮合)
            "2024-01-01T02:00:00Z",  # 强平(引擎行为,bar2)
            "2024-01-01T03:00:00Z",  # 重开仓落 bar3——泄漏形态会是 time=02:00 同 bar 成交
        ]
        assert trades[2].get("liquidation") is not True
        # bar3 快照价 38500×1.0005(泄漏形态会用 bar2 close 38500 同价但同 bar 时点成交)
        assert trades[2]["price"] == "38519.25000000"

    def test_callback_equity_same_basis_as_on_bar(self):
        """回调内 equity() 与同 bar on_bar 同用当前 bar close 口径(_last_close 在 BAR 节点
        开头、一切派发/撮合前更新),恒等式 equity = available + margin + unrealized 在
        on_fill 内成立——不再出现"equity 上一根 close 估值 vs upnl 当前 close"混口径。"""
        rows = [
            ("2024-01-01T00:00:00Z", "41000", "41000", "41000", "41000"),
            ("2024-01-01T01:00:00Z", "42000", "42000", "42000", "42000"),
            ("2024-01-01T02:00:00Z", "42000", "42000", "42000", "42000"),
        ]
        seen: dict = {}
        state = {"placed": False}

        def on_bar(bar, ctx):
            if not state["placed"]:
                ctx.place_order(
                    position_effect="OPEN_LONG", order_type="MARKET", amount=D("0.1"),
                    leverage=10, margin_mode="ISOLATED",
                )
                state["placed"] = True
            if bar.timestamp == "2024-01-01T01:00:00Z":
                seen["bar_equity"] = ctx.equity()

        def on_fill(fill, ctx):
            pos = ctx.position()
            seen["fill_equity"] = ctx.equity()
            # 恒等式:equity = available(cash−margin) + margin + unrealized = cash + unrealized
            seen["identity"] = ctx.equity() == ctx.available_cash() + pos.margin + pos.unrealized_pnl

        _perp_loop().run(on_bar, _perp_ctx(), _klines(rows), on_fill=on_fill)
        assert seen["fill_equity"] == seen["bar_equity"]  # 同 bar 两回调同口径
        assert seen["identity"]

    def test_spot_callback_equity_same_basis(self):
        """SPOT 同款口径一致性(equity = cash + holdings×当前 bar close,回调内成立)。"""
        klines = _klines(
            [
                ("2024-01-01T00:00:00Z", "90", "90", "90", "90"),
                ("2024-01-01T01:00:00Z", "100", "100", "100", "100"),
                ("2024-01-01T02:00:00Z", "100", "100", "100", "100"),
            ]
        )
        loop = BacktestEventLoop(initial_capital=D("10000"), symbol="BTC/USDT", timeframe="1h")
        ctx = BacktestContext(MagicMock(), task_id=1, symbol="BTC/USDT")
        seen: dict = {}
        state = {"placed": False}

        def on_bar(bar, ctx_):
            if not state["placed"]:
                ctx_.place_order(side="BUY", order_type="MARKET", amount=D("0.1"))
                state["placed"] = True
            if bar.timestamp == "2024-01-01T01:00:00Z":
                seen["bar_equity"] = ctx_.equity()

        def on_fill(fill, ctx_):
            seen["fill_equity"] = ctx_.equity()

        loop.run(on_bar, ctx, klines, on_fill=on_fill)
        # 旧行为:on_fill 内 equity 用上一根 close(90)估值,差 0.1×10 = 1;现在同 bar 同值
        assert seen["fill_equity"] == seen["bar_equity"]


# ---------------------------------------------------------------- 资金费 mark 真值化与归属


class TestFundingMarkTruth:
    def _capture_run(self, mark_price):
        periods = [
            FundingPeriod(funding_time=parse_instant("2024-01-01T01:30:00Z"), settled_rate=D("0.0001"),
                          interval_seconds=3600, mark_price=mark_price),
        ]
        events: list = []
        on_bar = _open_long_once([])

        def on_funding(ev, ctx):
            events.append(ev)

        out = _perp_loop(funding=periods).run(on_bar, _perp_ctx(), _flat_klines(3), on_funding=on_funding)
        return events, out

    def test_row_mark_price_truth_used(self):
        """期次行自带 mark_price → 用交易所真值(不再用 bar.close 代理,spec §5.3)。

        T=01:30 落在 bar1 span 中段(精确时间戳进 payload);amount = −0.0001×50000×0.1
        = −0.5(真值口径),≠ close 口径的 −0.42——判别性数值。"""
        events, out = self._capture_run(D("50000"))
        assert len(events) == 1
        ev = events[0]
        assert ev.funding_time == "2024-01-01T01:30:00Z"  # 精确中段时刻,非 bar 边界
        assert ev.mark_price == D("50000")
        assert ev.amount == D("-0.50000000")  # LONG 正费率 = 付(持仓视角带符号)
        assert ev.settled_rate == D("0.0001")
        assert ev.qty_at_settle == D("0.1")
        assert ev.symbol == "BTC/USDT"
        assert ev.source == "EXCHANGE"
        assert D(out["equity_curve"][-1]["funding_cum"]) == D("-0.50000000")

    def test_null_mark_falls_back_to_attributed_bar_close(self):
        """行 mark_price 缺失 → fallback 归属 bar close(旧口径数值,文档化近似)。"""
        events, out = self._capture_run(None)
        ev = events[0]
        assert ev.mark_price == D("42000")  # 归属 bar close
        assert ev.amount == D("-0.42000000")  # −0.0001×42000×0.1,与 bar 驱动时代一致
        assert D(out["equity_curve"][-1]["funding_cum"]) == D("-0.42000000")

    def test_non_positive_mark_treated_as_missing(self):
        """脏行守卫(spec §5.3):mark_price ≤0 视同缺失走 fallback——0 会静默零收费、
        负值会翻转资金费符号(付变收),都是错收;与 Java paper 调度器 signum≤0 守卫同纪律。"""
        for dirty in (D("0"), D("-100")):
            events, out = self._capture_run(dirty)
            ev = events[0]
            assert ev.mark_price == D("42000")  # fallback 归属 bar close,非脏值
            assert ev.amount == D("-0.42000000")
            assert D(out["equity_curve"][-1]["funding_cum"]) == D("-0.42000000")

    def test_stranded_tail_periods_warned(self):
        """K 线提前结束:任务 end 前未被任何 bar 归属窗消费的期次进 warnings 显性标注
        (spec §8 尾部漏期标注,"绝不静默漏收"的收尾防线);前瞻缓冲区(>end)期次不标注。"""
        periods = [
            # bar 只到 01:00(2 根),归属窗最远 (01:00, 02:00]——03:00 期在任务 end(04:00)前
            # 但无 bar 承载 → 漏期标注;05:00 期在 end 后(前瞻缓冲)→ 不标注
            FundingPeriod(funding_time=parse_instant("2024-01-01T03:00:00Z"), settled_rate=D("0.0001"),
                          interval_seconds=3600, mark_price=None),
            FundingPeriod(funding_time=parse_instant("2024-01-01T05:00:00Z"), settled_rate=D("0.0001"),
                          interval_seconds=3600, mark_price=None),
        ]
        on_bar = _open_long_once([])
        loop = BacktestEventLoop(
            initial_capital=D("100000"), symbol="BTC/USDT", timeframe="1h", market_type="PERP",
            pair_specs=PAIR_SPECS, funding_periods=periods, task_end="2024-01-01T04:00:00Z",
        )
        out = loop.run(on_bar, _perp_ctx(), _flat_klines(2))
        stranded = [w for w in out["warnings"] if "未参与回放" in w]
        assert len(stranded) == 1
        assert "1 期" in stranded[0] and "2024-01-01T03:00:00Z" in stranded[0]
        assert "05:00" not in stranded[0]  # 窗外期次不进标注

    def test_unparsable_task_end_degrades_to_warning(self):
        """纯诊断功能不得炸掉已完整跑完的回测:task_end 不可解析(手拼 config/非 tz-aware
        ISO)→ 尾部漏期检测降级为警示跳过,结果照常产出(生产路径 Jackson Instant→ISO
        恒合法,此为防御面——诊断代码 fail-fast 会把 warning 级问题放大成整份报告作废)。"""
        periods = [
            FundingPeriod(funding_time=parse_instant("2024-01-01T03:00:00Z"), settled_rate=D("0.0001"),
                          interval_seconds=3600, mark_price=None),
        ]
        on_bar = _open_long_once([])
        loop = BacktestEventLoop(
            initial_capital=D("100000"), symbol="BTC/USDT", timeframe="1h", market_type="PERP",
            pair_specs=PAIR_SPECS, funding_periods=periods, task_end="2024-01-02T00:00:00",  # naive 无时区
        )
        out = loop.run(on_bar, _perp_ctx(), _flat_klines(2))
        assert any("尾部漏期检测跳过" in w for w in out["warnings"])
        assert not any("未参与回放" in w for w in out["warnings"])
        assert out["equity_curve"]  # 结果照常产出,不因诊断解析失败作废


class TestFundingTimelineAttribution:
    def test_boundary_period_reflected_in_attributed_bar_equity(self):
        """T == bar 收盘时刻(右闭边界)归该 bar;equity 口径与 bar 驱动时代一致:
        本 bar 归属期次在本 bar equity 点即反映(§5.2)。"""
        periods = [
            FundingPeriod(funding_time=parse_instant("2024-01-01T02:00:00Z"), settled_rate=D("0.0001"),
                          interval_seconds=3600, mark_price=None),
        ]
        on_bar = _open_long_once([])
        out = _perp_loop(funding=periods).run(on_bar, _perp_ctx(), _flat_klines(4))
        curve = out["equity_curve"]
        assert D(curve[0]["funding_cum"]) == 0  # bar0:开仓单已排未成交,flat 跳过
        assert D(curve[1]["funding_cum"]) == D("-0.42000000")  # bar1 点已含归属期次
        assert D(curve[2]["funding_cum"]) == D("-0.42000000")  # 无新期次,累计不变

    def test_flat_period_not_dispatched(self):
        """flat 期次:不结算不派发(§5.5,与 runner"无结算落账行即无事件"同构)。"""
        periods = [
            FundingPeriod(funding_time=parse_instant("2024-01-01T01:00:00Z"), settled_rate=D("0.0001"),
                          interval_seconds=3600, mark_price=None),
        ]
        events: list = []

        def on_funding(ev, ctx):
            events.append(ev)

        out = _perp_loop(funding=periods).run(
            lambda bar, ctx: None, _perp_ctx(), _flat_klines(3), on_funding=on_funding
        )
        assert events == []
        assert all(D(e["funding_cum"]) == 0 for e in out["equity_curve"])

    def test_proxy_source_passed_through(self):
        """跨所代理期次:source 原样进 payload(策略可感知基差风险期次)。"""
        periods = [
            FundingPeriod(funding_time=parse_instant("2024-01-01T02:00:00Z"), settled_rate=D("0.0001"),
                          interval_seconds=3600, mark_price=None, source="PROXY_BINANCE"),
        ]
        events: list = []
        on_bar = _open_long_once([])
        _perp_loop(funding=periods).run(
            on_bar, _perp_ctx(), _flat_klines(3), on_funding=lambda ev, ctx: events.append(ev)
        )
        assert events[0].source == "PROXY_BINANCE"


# ---------------------------------------------------------------- 回调内下单与异常语义


class TestCallbackOrdersAndExceptions:
    def _spot_setup(self):
        klines = _klines(
            [
                ("2024-01-01T00:00:00Z", "100", "100", "100", "100"),
                ("2024-01-01T01:00:00Z", "100", "100", "100", "100"),
                ("2024-01-01T02:00:00Z", "100", "100", "100", "100"),
                ("2024-01-01T03:00:00Z", "100", "100", "100", "100"),
            ]
        )
        loop = BacktestEventLoop(initial_capital=D("10000"), symbol="BTC/USDT", timeframe="1h")
        ctx = BacktestContext(MagicMock(), task_id=1, symbol="BTC/USDT")
        return loop, ctx, klines

    def test_on_fill_order_queued_next_bar(self):
        """回调内 place_order 与 on_bar 同一意图队列,NEXT_BAR 撮合(matching-spec §7)。"""
        loop, ctx, klines = self._spot_setup()
        state = {"placed": False}

        def on_bar(bar, ctx):
            if not state["placed"]:
                ctx.place_order(side="BUY", order_type="MARKET", amount=D("0.1"))
                state["placed"] = True

        def on_fill(fill, ctx):
            if fill.side == "BUY":
                ctx.place_order(side="SELL", order_type="MARKET", amount=D("0.1"))

        out = loop.run(on_bar, ctx, klines, on_fill=on_fill)
        # bar0 下 BUY → bar1 成交(on_fill 内下 SELL)→ bar2 成交
        assert [t["time"] for t in out["trades"]] == [
            "2024-01-01T01:00:00Z",
            "2024-01-01T02:00:00Z",
        ]

    def test_callback_exception_fail_fast(self):
        """回调异常与 on_bar 同级 fail-fast:RuntimeError 带回调名与时点。"""
        loop, ctx, klines = self._spot_setup()
        state = {"placed": False}

        def on_bar(bar, ctx_):
            if not state["placed"]:
                ctx_.place_order(side="BUY", order_type="MARKET", amount=D("0.1"))
                state["placed"] = True

        def bad_fill(fill, ctx_):
            raise ValueError("boom")

        with pytest.raises(RuntimeError, match=r"strategy on_fill failed at 2024-01-01T01:00:00Z"):
            loop.run(on_bar, ctx, klines, on_fill=bad_fill)

    def test_on_funding_exception_fail_fast(self):
        periods = [
            FundingPeriod(funding_time=parse_instant("2024-01-01T02:00:00Z"), settled_rate=D("0.0001"),
                          interval_seconds=3600, mark_price=None),
        ]
        on_bar = _open_long_once([])

        def bad_funding(ev, ctx):
            raise ValueError("boom")

        with pytest.raises(RuntimeError, match=r"strategy on_funding failed at 2024-01-01T02:00:00Z"):
            _perp_loop(funding=periods).run(on_bar, _perp_ctx(), _flat_klines(3), on_funding=bad_funding)


# ---------------------------------------------------------------- SPOT / 组合 on_fill


class TestSpotAndPortfolioFillEvents:
    def test_spot_fill_payload_fields(self):
        """SPOT 成交 payload 全字段:order_id 引擎序号递增、liquidity、position_effect 恒 None。"""
        klines = _klines(
            [
                ("2024-01-01T00:00:00Z", "100", "100", "100", "100"),
                ("2024-01-01T01:00:00Z", "100", "100", "100", "100"),
                ("2024-01-01T02:00:00Z", "100", "100", "100", "100"),
                ("2024-01-01T03:00:00Z", "100", "100", "100", "100"),
            ]
        )
        loop = BacktestEventLoop(initial_capital=D("10000"), symbol="BTC/USDT", timeframe="1h")
        ctx = BacktestContext(MagicMock(), task_id=1, symbol="BTC/USDT")
        events: list = []
        positions_seen: list = []
        state = {"n": 0}

        def on_bar(bar, ctx_):
            state["n"] += 1
            if state["n"] == 1:
                ctx_.place_order(side="BUY", order_type="MARKET", amount=D("0.1"))
            elif state["n"] == 3:
                ctx_.place_order(side="SELL", order_type="MARKET", amount=D("0.1"))

        def on_fill(fill, ctx_):
            events.append(fill)
            positions_seen.append(ctx_.position().qty)

        loop.run(on_bar, ctx, klines, on_fill=on_fill)
        assert len(events) == 2
        f = events[0]
        assert isinstance(f, FillEvent)
        assert f.symbol == "BTC/USDT" and f.side == "BUY"
        assert f.price == D("100.05000000")  # 100 × 1.0005
        assert f.qty == D("0.1")
        assert f.fee == D("0.02001000")  # 100.05 × 0.1 × 0.002
        assert f.fee_currency == "USDT"
        assert f.filled_at == "2024-01-01T01:00:00Z"
        assert f.order_id == 1 and f.liquidity == "taker" and f.position_effect is None
        assert events[1].order_id == 2 and events[1].side == "SELL"
        assert events[1].price == D("99.95000000")  # 100 × 0.9995
        # 派发前账本已落账:回调内 position() 已含本笔成交
        assert positions_seen == [D("0.1"), D("0")]

    def test_portfolio_dispatches_per_symbol(self):
        """组合回测:仅 FILL 事件,逐标的 payload(symbol 必带,共享 order_id 序号)。"""

        def _k(closes: list[str]):
            return [
                {"timestamp": f"t{i:04d}", "open": c, "high": c, "low": c, "close": c, "volume": "1"}
                for i, c in enumerate(closes)
            ]

        series = {"AAA/USDT": _k(["100", "110", "120"]), "BBB/USDT": _k(["50", "55", "60"])}
        events: list = []
        state = {"done": False}

        def on_bars(ctx):
            if not state["done"]:
                ctx.place_order(symbol="AAA/USDT", side="BUY", order_type="MARKET", amount=D("1"))
                state["done"] = True

        def on_fill(fill, ctx):
            events.append((fill.symbol, fill.side, str(fill.price), ctx.position(fill.symbol).qty))

        ctx = PortfolioContext(MagicMock(), task_id=1, symbols=["AAA/USDT", "BBB/USDT"])
        loop = PortfolioEventLoop(
            initial_capital=D("10000"), symbols=["AAA/USDT", "BBB/USDT"], timeframe="1h"
        )
        loop.run(on_bars, ctx, series, on_fill=on_fill)
        # t0000 下单 → t0001 撮合(NEXT_BAR,AAA close=110):110 × 1.0005 = 110.055
        assert events == [("AAA/USDT", "BUY", "110.05500000", D("1"))]


# ---------------------------------------------------------------- runner WS 转换与派发


class TestRunnerWsConversion:
    def test_fill_event_from_ws_maps_fields_with_decimal_defense(self):
        """WS 金额是 JSON number(契约缺口)→ Decimal(str) 防御转换,不经 float 运算。"""
        ev = _fill_event_from_ws(
            {
                "eventType": "NEW_FILL",
                "fillId": 88,
                "orderId": 42,
                "accountId": 7,
                "symbol": "BTC/USDT",
                "side": "BUY",
                "price": 42150.5,
                "qty": 0.1,
                "fee": 0.4215,
                "feeCurrency": "USDT",
                "liquidity": "taker",
                "positionEffect": "OPEN_LONG",
                "filledAt": "2024-01-15T08:00:01Z",
            }
        )
        assert isinstance(ev, FillEvent)
        assert ev.symbol == "BTC/USDT" and ev.side == "BUY"
        assert ev.price == D("42150.5") and ev.qty == D("0.1") and ev.fee == D("0.4215")
        assert ev.fee_currency == "USDT" and ev.liquidity == "taker"
        assert ev.position_effect == "OPEN_LONG"
        assert ev.order_id == 42 and ev.filled_at == "2024-01-15T08:00:01Z"

    def test_fill_event_from_ws_malformed_returns_none(self):
        assert _fill_event_from_ws({"symbol": "BTC/USDT", "side": "BUY"}) is None  # 缺金额字段
        assert _fill_event_from_ws(
            {"symbol": "BTC/USDT", "side": "BUY", "price": "abc", "qty": "1", "fee": "0"}
        ) is None  # 金额非法
        assert _fill_event_from_ws({"side": "BUY", "price": 1, "qty": 1, "fee": 0}) is None  # 缺 symbol

    def test_liquidation_event_from_ws_nullable_fields(self):
        ev = _liquidation_event_from_ws(
            {
                "userId": 42,
                "orderId": None,
                "accountId": 7,
                "positionId": 128,
                "symbol": "BTC/USDT",
                "positionSide": "LONG",
                "qty": 0.5,
                "leverage": 10,
                "marginMode": "ISOLATED",
                "liquidationPrice": None,  # 派生未算出 → price None(契约可空)
                "markPrice": 42300.0,
                "marginBalance": 40.0,
                "realizedPnl": -2.5,
                "reason": "liquidation triggered at markPrice=42300.00",
                "timestamp": "2026-07-21T08:00:00Z",
            }
        )
        assert isinstance(ev, LiquidationEvent)
        assert ev.symbol == "BTC/USDT" and ev.position_side == "LONG"
        assert ev.qty == D("0.5") and ev.price is None
        assert ev.realized_pnl == D("-2.5") and ev.margin_mode == "ISOLATED"
        assert ev.reason == "liquidation triggered at markPrice=42300.00"
        assert ev.timestamp == "2026-07-21T08:00:00Z"
        # 必填缺失 → None
        assert _liquidation_event_from_ws({"symbol": "BTC/USDT", "positionSide": "LONG"}) is None

    def test_funding_event_from_ws_field_mapping(self):
        ev = _funding_event_from_ws(
            {
                "userId": 42,
                "accountId": 7,
                "positionId": 128,
                "symbol": "BTC/USDT",
                "fundingRate": 0.0001,
                "qtyAtSettle": 0.0025,
                "fundingAmount": -0.0125,
                "settleTime": "2026-08-05T08:00:00Z",
                "billId": "530758662684151809",
                "timestamp": "2026-08-05T08:00:00.123Z",
            }
        )
        assert isinstance(ev, FundingEvent)
        assert ev.symbol == "BTC/USDT"
        assert ev.funding_time == "2026-08-05T08:00:00Z"  # settleTime → funding_time
        assert ev.settled_rate == D("0.0001")
        assert ev.amount == D("-0.0125") and ev.qty_at_settle == D("0.0025")
        assert ev.mark_price is None and ev.source is None  # WS 载荷无此字段(契约恒 None)
        assert _funding_event_from_ws({"symbol": "BTC/USDT"}) is None  # 缺 fundingAmount


class TestRunnerDispatch:
    def _stream(self):
        stream = MagicMock()

        async def _noop():
            return None

        stream.run = _noop  # asyncio.run(stream.run()) 立即返回
        return stream

    def test_run_subscribes_only_defined_callbacks(self):
        """按需订阅:定义了 on_fill 才订 /topic/fills;未定义的不订阅(省后端 fanout)。"""
        stream = self._stream()
        loop = RunnerEventLoop()
        loop.run(
            lambda bar, ctx: None,
            MagicMock(),
            stream,
            exchange="OKX",
            market_type="PERP",
            symbol="BTC/USDT",
            interval="1h",
            user_id=42,
            on_fill=lambda f, c: None,
        )
        stream.on_kline.assert_called_once()
        stream.on_tick.assert_called_once()  # ticker no-op 订阅保留(PaperExecutor 撮合驱动)
        args = stream.on_fill.call_args[0]
        assert args[0] == 42 and callable(args[1])
        stream.on_liquidation.assert_not_called()
        stream.on_funding.assert_not_called()

    def test_run_missing_user_id_skips_event_topics_with_stderr(self, capsys):
        stream = self._stream()
        loop = RunnerEventLoop()
        loop.run(
            lambda bar, ctx: None,
            MagicMock(),
            stream,
            exchange="OKX",
            market_type="SPOT",
            symbol="BTC/USDT",
            interval="1h",
            user_id=None,
            on_fill=lambda f, c: None,
        )
        stream.on_fill.assert_not_called()
        assert "userId missing" in capsys.readouterr().err

    def test_dispatch_filters_by_symbol_and_converts(self):
        loop = RunnerEventLoop()
        loop._symbol = "BTC/USDT"
        loop._ctx = MagicMock()
        seen: list = []

        def cb(ev, ctx):
            seen.append(ev)

        foreign = {"symbol": "ETH/USDT", "side": "BUY", "price": 1, "qty": 1, "fee": 0}
        asyncio.run(loop._dispatch_ws_event(foreign, _fill_event_from_ws, cb, "on_fill"))
        assert seen == []  # user 级 topic 推全账户事件,非绑定 symbol 不派发

        mine = {"symbol": "BTC/USDT", "side": "BUY", "price": "42150.5", "qty": "0.1", "fee": "0.4"}
        asyncio.run(loop._dispatch_ws_event(mine, _fill_event_from_ws, cb, "on_fill"))
        assert len(seen) == 1 and isinstance(seen[0], FillEvent) and seen[0].price == D("42150.5")

    def test_dispatch_filters_by_account_id(self):
        """user 级 topic 覆盖该用户**全部账户**(PAPER/LIVE 多账户并存是产品常态):
        非绑定账户的事件不派发(模拟盘/实盘强区分红线——paper 成交进 live 策略回调
        会污染其累计均价/计数并触发错误下单);payload 缺 accountId(旧后端版本偏斜)
        时跳过该层过滤,不 fail-closed 断整个回调通道。"""
        loop = RunnerEventLoop()
        loop._symbol = "BTC/USDT"
        loop._account_id = 7
        loop._ctx = MagicMock()
        seen: list = []

        def cb(ev, ctx):
            seen.append(ev)

        base = {"symbol": "BTC/USDT", "side": "BUY", "price": "1", "qty": "1", "fee": "0"}
        asyncio.run(loop._dispatch_ws_event({**base, "accountId": 99}, _fill_event_from_ws, cb, "on_fill"))
        assert seen == []  # 另一账户(如 PAPER)的成交不进本账户(如 LIVE)策略回调
        asyncio.run(loop._dispatch_ws_event({**base, "accountId": 7}, _fill_event_from_ws, cb, "on_fill"))
        assert len(seen) == 1
        asyncio.run(loop._dispatch_ws_event(dict(base), _fill_event_from_ws, cb, "on_fill"))
        assert len(seen) == 2  # 载荷缺 accountId(版本偏斜)→ 放行

    def test_fill_dispatch_filters_by_market_type(self):
        """同一账户 SPOT+PERP 可同 symbol 并存、fills topic 两路共用:只派发本策略
        市场类型的成交;payload 缺 marketType(旧后端版本偏斜)时跳过该层过滤。"""
        loop = RunnerEventLoop()
        loop._symbol = "BTC/USDT"
        loop._market_type = "SPOT"
        loop._ctx = MagicMock()
        seen: list = []
        loop._on_fill = lambda ev, ctx: seen.append(ev)

        base = {"symbol": "BTC/USDT", "side": "BUY", "price": "1", "qty": "1", "fee": "0"}
        asyncio.run(loop._on_fill_ws({**base, "marketType": "PERP"}))
        assert seen == []  # 同账户 PERP 成交不进 SPOT 策略回调
        asyncio.run(loop._on_fill_ws({**base, "marketType": "SPOT"}))
        assert len(seen) == 1
        asyncio.run(loop._on_fill_ws(dict(base)))
        assert len(seen) == 2  # 缺 marketType(偏斜容忍)→ 放行

    def test_malformed_payload_skipped_not_raised(self, capsys):
        loop = RunnerEventLoop()
        loop._symbol = "BTC/USDT"
        loop._ctx = MagicMock()
        cb = MagicMock()
        asyncio.run(
            loop._dispatch_ws_event(
                {"symbol": "BTC/USDT", "side": "BUY"},  # 缺金额字段
                _fill_event_from_ws,
                cb,
                "on_fill",
            )
        )
        cb.assert_not_called()
        assert "malformed on_fill payload skipped" in capsys.readouterr().err

    def test_invoke_callback_exception_logged_not_raised(self, capsys):
        """runner 回调异常记 stderr 继续(与 on_bar 同语义,长驻进程不因单事件而死)。"""
        loop = RunnerEventLoop()
        loop._ctx = MagicMock()

        def bad(ev, ctx):
            raise ValueError("boom")

        loop._invoke_callback(bad, object(), "on_funding")  # 不抛
        assert "on_funding raised" in capsys.readouterr().err

    def test_spot_runner_skips_funding_topic_with_stderr(self, capsys):
        """SPOT 策略定义 on_funding:不白订 /topic/funding(Java 只对 PERP 结算推送)+ 出声提示。"""
        stream = self._stream()
        loop = RunnerEventLoop()
        loop.run(
            lambda bar, ctx: None,
            MagicMock(),
            stream,
            exchange="OKX",
            market_type="SPOT",
            symbol="BTC/USDT",
            interval="1h",
            user_id=42,
            on_funding=lambda ev, ctx: None,
        )
        stream.on_funding.assert_not_called()
        assert "no funding events" in capsys.readouterr().err

    def test_spot_runner_skips_liquidation_topic_with_stderr(self, capsys):
        """SPOT 策略定义 on_liquidation:不白订 /topic/liquidations(SPOT 无强平)+ 出声
        提示——与 on_funding 同款纪律、与回测路径 SPOT 提示一致(两运行时作者反馈一致)。"""
        stream = self._stream()
        loop = RunnerEventLoop()
        loop.run(
            lambda bar, ctx: None,
            MagicMock(),
            stream,
            exchange="OKX",
            market_type="SPOT",
            symbol="BTC/USDT",
            interval="1h",
            user_id=42,
            on_liquidation=lambda ev, ctx: None,
        )
        stream.on_liquidation.assert_not_called()
        assert "no liquidation events" in capsys.readouterr().err

    def test_missing_account_id_degrades_to_symbol_filter_with_stderr(self, capsys):
        """旧 bootstrap 无 accountId:订阅照常但账户过滤层缺失要出声(同用户其他账户的
        事件可能进回调,不静默破坏账户隔离)——与 userId 缺失降级**订阅**相区分
        (userId 缺失连 topic 都无法构造)。"""
        stream = self._stream()
        loop = RunnerEventLoop()
        loop.run(
            lambda bar, ctx: None,
            MagicMock(),
            stream,
            exchange="OKX",
            market_type="SPOT",
            symbol="BTC/USDT",
            interval="1h",
            user_id=42,
            account_id=None,
            on_fill=lambda f, c: None,
        )
        stream.on_fill.assert_called_once()  # 订阅照常(fail-open,回调功能不断)
        assert "accountId missing" in capsys.readouterr().err

    def test_symbol_mismatch_skipped_with_rate_limited_stderr(self, capsys):
        """symbol 不匹配限次出声(过滤键持续失配 = 绑定/数据口径错位,静默丢弃不可观测;
        user 级 topic 推全账户事件,不匹配是常态,按通道分桶限 3 次防刷屏)。"""
        loop = RunnerEventLoop()
        loop._symbol = "BTC/USDT"
        loop._ctx = MagicMock()
        cb = MagicMock()
        foreign = {"symbol": "ETH/USDT", "side": "BUY", "price": "1", "qty": "1", "fee": "0"}
        for _ in range(5):
            asyncio.run(loop._dispatch_ws_event(foreign, _fill_event_from_ws, cb, "on_fill"))
        cb.assert_not_called()
        assert capsys.readouterr().err.count("skipped") == 3
        # 分桶独立:fills 通道限额用尽后,liquidations 通道的失配仍出声(共享单计数会永久静默)
        foreign_liq = {"symbol": "ETH/USDT", "positionSide": "LONG", "qty": "1"}
        asyncio.run(loop._dispatch_ws_event(foreign_liq, _liquidation_event_from_ws, cb, "on_liquidation"))
        assert capsys.readouterr().err.count("skipped") == 1

    def test_ws_event_dispatch_does_not_touch_liveness(self):
        """探活语义锁(P2-3 防回退):事件通道**不**刷新 lastWsMsgAt——探活源唯一是 kline
        行情流。fills/funding 事件流量新鲜不能掩盖 kline 停摆,否则 Java"WS 断>5min→
        restart"自愈通道被掐断(on_bar 停摆却探活恒绿)。"""
        from kwikquant_worker.health_signals import HealthSignals

        signals = HealthSignals(7)
        loop = RunnerEventLoop(health_signals=signals)
        loop._symbol = "BTC/USDT"
        loop._ctx = MagicMock()
        payload = {"symbol": "BTC/USDT", "side": "BUY", "price": "1", "qty": "1", "fee": "0"}
        asyncio.run(loop._dispatch_ws_event(payload, _fill_event_from_ws, lambda ev, ctx: None, "on_fill"))
        assert signals.snapshot()["lastWsMsgAt"] is None  # 事件派发不 touch
        loop._touch_ws()  # 对照:kline 通道(_on_kline)才 touch
        assert signals.snapshot()["lastWsMsgAt"] is not None

    def test_callback_failure_counts_independently_not_degrading(self, capsys):
        """事件回调失败走**独立**计数,不驱动 degraded/restart:事件是一次性 WS 推送、
        restart 后不重放,瞬时回调异常触发 restart 纯丢策略内存态;低频回调(funding 8h
        一期)的 degraded 窗口远超 Java 探活阈值(30s×2),并入 status 会让 restart 成为
        常态。restart 通道仍只由 on_bar 持续失败驱动(on_bar 每 bar 必调=失能判据成立)。"""
        from kwikquant_worker.health_signals import HealthSignals

        signals = HealthSignals(7)
        loop = RunnerEventLoop(health_signals=signals)
        loop._ctx = MagicMock()

        def bad(ev, ctx):
            raise ValueError("boom")

        loop._invoke_callback(bad, object(), "on_fill")
        snap = signals.snapshot()
        assert snap["status"] == "ok"  # 回调失败不进 degraded 判定(restart 通道不被触发)
        assert snap["consecutiveCallbackFailures"] == 1  # 独立计数供 /health 观测
        assert snap["consecutiveOnBarFailures"] == 0
        # 下一次成功重置(与 on_bar 同款语义)
        loop._invoke_callback(lambda ev, ctx: None, object(), "on_fill")
        snap = signals.snapshot()
        assert snap["consecutiveCallbackFailures"] == 0 and snap["status"] == "ok"
        # 对照:on_bar 失败仍驱动 degraded(status 派生不变,test_health_signals 锁推导细节)
        loop._record_on_bar(ok=False)
        assert signals.snapshot()["status"] == "degraded"

    def test_strategy_callbacks_serial_on_dedicated_thread(self):
        """单线程 executor 结构保证:并发提交 → 按提交序串行执行 + 同一工作线程
        (策略模块级状态无并发交错,threading.local 可用;与回测单线程语义对齐)。"""
        import threading

        loop = RunnerEventLoop()
        loop._ctx = MagicMock()
        records: list = []

        def cb(tag):
            records.append((tag, threading.current_thread().name))

        async def go():
            running = asyncio.get_running_loop()
            await asyncio.gather(
                running.run_in_executor(loop._executor, cb, "first"),
                running.run_in_executor(loop._executor, cb, "second"),
            )

        asyncio.run(go())
        assert [r[0] for r in records] == ["first", "second"]  # FIFO 串行
        assert records[0][1] == records[1][1]  # 同一工作线程
        assert "kq-strategy" in records[0][1]
