"""BacktestEventLoop PERP 端到端 — docs/perp-backtest-spec.md §2/§6/§8 编排语义。

账本数学由 test_perp_ledger.py 锁定,本层验证:place_order PERP 契约、acceptance 接线、
逐 bar 闸门顺序(强平先于撮合)、资金费回放接线、section8 输出扩展(条件字段/负零规范)、
ctx.position 视图。期望值手算(MARKET fill = last×1.0005,taker 0.002,lev 10)。
"""

from __future__ import annotations

from decimal import Decimal
from unittest.mock import MagicMock

import pytest

from kwikquant_worker.backtest.perp_ledger import FundingPeriod, parse_instant
from kwikquant_worker.event_loop import BacktestEventLoop
from kwikquant_worker.portfolio import PortfolioContext
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

# 关键手算值(MARKET BUY @ close 42000,滑点 5bps):
FILL_PX = D("42021.00000000")  # 42000 × 1.00050000
OPEN_FEE = D("8.40420000")  # 42021 × 0.1 × 0.002
IM = D("420.21000000")  # 42021 × 0.1 / 10
LIQ_REF = D("38008.94472362")  # (4202.1 − 420.21) / (0.1 × 0.995)


def _klines(rows: list[tuple[str, str, str, str, str]]):
    return [
        {"timestamp": ts, "open": o, "high": h, "low": lo, "close": c, "volume": "10"}
        for ts, o, h, lo, c in rows
    ]


def _flat_klines(n: int, px: str = "42000"):
    return _klines([(f"2024-01-01T0{i}:00:00Z", px, px, px, px) for i in range(n)])


def _ctx(market_type: str = "PERP") -> BacktestContext:
    return BacktestContext(MagicMock(), task_id=1, market_type=market_type, symbol="BTC/USDT")


def _loop(funding=None, **over) -> BacktestEventLoop:
    kw = {
        "initial_capital": D("100000"),
        "symbol": "BTC/USDT",
        "timeframe": "1h",
        "market_type": "PERP",
        "pair_specs": PAIR_SPECS,
        "funding_periods": [] if funding is None else funding,
    }
    kw.update(over)
    return BacktestEventLoop(**kw)


class TestPlaceOrderContract:
    def test_perp_rejects_explicit_side(self):
        ctx = _ctx()
        with pytest.raises(ValueError, match="禁传 side"):
            ctx.place_order(side="BUY", position_effect="OPEN_LONG", order_type="MARKET",
                            amount=D("0.1"), leverage=10, margin_mode="ISOLATED")

    def test_perp_requires_position_effect(self):
        ctx = _ctx()
        with pytest.raises(ValueError, match="position_effect 非法"):
            ctx.place_order(order_type="MARKET", amount=D("0.1"), leverage=10, margin_mode="ISOLATED")

    def test_perp_rejects_bad_margin_mode(self):
        ctx = _ctx()
        with pytest.raises(ValueError, match="margin_mode 非法"):
            ctx.place_order(position_effect="OPEN_LONG", order_type="MARKET", amount=D("0.1"),
                            leverage=10, margin_mode="FOO")

    def test_perp_rejects_float_leverage(self):
        ctx = _ctx()
        with pytest.raises(ValueError, match="leverage 非法"):
            ctx.place_order(position_effect="OPEN_LONG", order_type="MARKET", amount=D("0.1"),
                            leverage=10.5, margin_mode="ISOLATED")

    def test_perp_side_derived_from_effect(self):
        ctx = _ctx()
        ctx.place_order(position_effect="OPEN_SHORT", order_type="MARKET", amount=D("0.1"),
                        leverage=10, margin_mode="ISOLATED")
        intent = ctx._pending[0]
        assert intent.side == "SELL" and intent.position_effect == "OPEN_SHORT"

    def test_spot_rejects_contract_fields(self):
        ctx = _ctx(market_type="SPOT")
        with pytest.raises(ValueError, match="SPOT 不得传"):
            ctx.place_order(side="BUY", order_type="MARKET", amount=D("0.1"), position_effect="OPEN_LONG")

    def test_portfolio_perp_rejected(self):
        ctx = PortfolioContext(MagicMock(), task_id=1, market_type="PERP", symbols=["BTC/USDT", "ETH/USDT"])
        with pytest.raises(ValueError, match="SPOT-only"):
            ctx.place_order(symbol="BTC/USDT", side="BUY", order_type="MARKET", amount=D("0.1"))


class TestPerpRun:
    def test_requires_funding_periods(self):
        loop = _loop(funding=None)
        loop._funding_periods = None
        with pytest.raises(ValueError, match="funding_periods"):
            loop.run(lambda bar, ctx: None, _ctx(), _flat_klines(2))

    def test_open_long_end_to_end_output_shape(self):
        state = {"placed": False}

        def on_bar(bar, ctx):
            if not state["placed"]:
                ctx.place_order(position_effect="OPEN_LONG", order_type="MARKET", amount=D("0.1"),
                                leverage=10, margin_mode="ISOLATED")
                state["placed"] = True

        out = _loop().run(on_bar, _ctx(), _flat_klines(3))

        assert out["market_type"] == "PERP"
        assert out["liquidation_model"] == "BAR_EXTREME_APPROX"
        assert out["warnings"] == []
        # trades:一条,PERP 条件字段
        assert len(out["trades"]) == 1
        tr = out["trades"][0]
        assert tr["side"] == "buy" and tr["position_effect"] == "OPEN_LONG"
        assert "liquidation" not in tr
        assert D(tr["price"]) == FILL_PX and D(tr["amount"]) == D("0.1") and D(tr["fee"]) == OPEN_FEE
        assert tr["time"] == "2024-01-01T01:00:00Z"  # NEXT_BAR
        # equity_curve:PERP 额外列;bar0 未成交 margin_used=0(flat),bar1 起持仓
        assert len(out["equity_curve"]) == 3
        assert D(out["equity_curve"][0]["margin_used"]) == 0
        assert str(D(out["equity_curve"][0]["funding_cum"])) == "0"  # 负零规范:-0 不得出现
        assert D(out["equity_curve"][1]["margin_used"]) == IM
        # equity(bar1 close=42000)= 99991.5958 − 2.1
        assert D(out["equity_curve"][1]["equity"]) == D("99989.49580000")
        assert D(out["equity_curve"][2]["equity"]) == D("99989.49580000")

    def test_position_view_visible_to_strategy(self):
        seen = {}
        state = {"placed": False}

        def on_bar(bar, ctx):
            if not state["placed"]:
                ctx.place_order(position_effect="OPEN_LONG", order_type="MARKET", amount=D("0.1"),
                                leverage=10, margin_mode="ISOLATED")
                state["placed"] = True
            elif bar.timestamp == "2024-01-01T01:00:00Z":
                p = ctx.position("BTC/USDT")
                seen.update(qty=p.qty, avg=p.avg_price, lev=p.leverage, mode=p.margin_mode,
                            margin=p.margin, liq=p.liquidation_price, upnl=p.unrealized_pnl)

        _loop().run(on_bar, _ctx(), _flat_klines(3))

        assert seen["qty"] == D("0.1")  # signed 净持仓
        assert seen["avg"] == FILL_PX
        assert seen["lev"] == 10 and seen["mode"] == "ISOLATED"
        assert seen["margin"] == IM
        assert seen["liq"] == LIQ_REF
        assert seen["upnl"] == D("-2.1")  # (42000 − 42021) × 0.1

    def test_acceptance_rejection_into_warnings(self):
        state = {"placed": False}

        def on_bar(bar, ctx):
            if not state["placed"]:
                # amount 0.0001 < minQty 0.001 → MIN_QTY 拒(不静默)
                ctx.place_order(position_effect="OPEN_LONG", order_type="MARKET", amount=D("0.0001"),
                                leverage=10, margin_mode="ISOLATED")
                state["placed"] = True

        out = _loop().run(on_bar, _ctx(), _flat_klines(3))
        assert out["trades"] == []
        assert any("MIN_QTY: amount 0.0001 < minQty 0.001" in w for w in out["warnings"])

    def test_missing_pair_spec_fail_closed(self):
        state = {"placed": False}

        def on_bar(bar, ctx):
            if not state["placed"]:
                ctx.place_order(position_effect="OPEN_LONG", order_type="MARKET", amount=D("0.1"),
                                leverage=10, margin_mode="ISOLATED")
                state["placed"] = True

        out = _loop(pair_specs=None).run(on_bar, _ctx(), _flat_klines(3))
        assert out["trades"] == []
        assert any("UNKNOWN_SYMBOL" in w for w in out["warnings"])

    def test_close_over_position_rejected(self):
        calls = {"n": 0}

        def on_bar(bar, ctx):
            calls["n"] += 1
            if calls["n"] == 1:
                ctx.place_order(position_effect="OPEN_LONG", order_type="MARKET", amount=D("0.1"),
                                leverage=10, margin_mode="ISOLATED")
            elif calls["n"] == 3:
                ctx.place_order(position_effect="CLOSE_LONG", order_type="MARKET", amount=D("0.2"))

        out = _loop().run(on_bar, _ctx(), _flat_klines(4))
        assert len(out["trades"]) == 1  # 只有开仓成交
        assert any("CLOSE_OVER_POSITION" in w for w in out["warnings"])

    def test_leverage_inherited_when_omitted(self):
        calls = {"n": 0}

        def on_bar(bar, ctx):
            calls["n"] += 1
            if calls["n"] == 1:
                ctx.place_order(position_effect="OPEN_LONG", order_type="MARKET", amount=D("0.1"),
                                leverage=10, margin_mode="ISOLATED")
            elif calls["n"] == 3:
                # 加仓省略 leverage/margin_mode → 继承持仓(spec §2)
                ctx.place_order(position_effect="OPEN_LONG", order_type="MARKET", amount=D("0.1"))

        out = _loop().run(on_bar, _ctx(), _flat_klines(4))
        assert len(out["trades"]) == 2
        assert out["warnings"] == []

    def test_flip_via_open_short_one_trade(self):
        calls = {"n": 0}

        def on_bar(bar, ctx):
            calls["n"] += 1
            if calls["n"] == 1:
                ctx.place_order(position_effect="OPEN_LONG", order_type="MARKET", amount=D("0.1"),
                                leverage=10, margin_mode="ISOLATED")
            elif calls["n"] == 3:
                ctx.place_order(position_effect="OPEN_SHORT", order_type="MARKET", amount=D("0.15"))

        out = _loop().run(on_bar, _ctx(), _flat_klines(4))
        # 反转:用户视角一条 trade(内核两段);bar3 撮合 SELL fill = 42000×0.9995 = 41979
        assert len(out["trades"]) == 2
        flip = out["trades"][1]
        assert flip["side"] == "sell" and flip["position_effect"] == "OPEN_SHORT"
        assert D(flip["price"]) == D("41979.00000000")
        # 末仓:SHORT 0.05 @ 41979;equity = cash + unrealized(close 42000)
        # cash = 99991.5958 + (41979−42021)×0.1 − 41979×0.15×0.002 = 99991.5958 − 4.2 − 12.5937
        # margin = 41979×0.05/10 = 209.895;unrealized = (41979−42000)×0.05 = −1.05
        last = out["equity_curve"][-1]
        assert D(last["margin_used"]) == D("209.89500000")
        assert D(last["equity"]) == D("99991.5958") - D("4.2") - D("12.5937") - D("1.05")

    def test_liquidation_wick_before_matching(self):
        calls = {"n": 0}

        def on_bar(bar, ctx):
            calls["n"] += 1
            if calls["n"] == 1:
                ctx.place_order(position_effect="OPEN_LONG", order_type="MARKET", amount=D("0.1"),
                                leverage=10, margin_mode="ISOLATED")
            elif calls["n"] == 2:
                # 顺序契约(spec §6 步骤 1):本单在 bar2 排队,bar3(插针)撮合段消费——
                # 强平必须先于撮合:bar3 先强平 → 本单撞 flat → CLOSE_OVER_POSITION 拒。
                # 若把 check_liquidation 挪到意图循环之后,本单会先按 bar3 价格成交平掉仓位,
                # 强平不再触发(liq_trades==0),本测试红——旧版把单排在 bar3 自身,撮合段
                # take_pending 为空,怎么挪顺序都绿(假绿)
                ctx.place_order(position_effect="CLOSE_LONG", order_type="MARKET", amount=D("0.1"),
                                leverage=10, margin_mode="ISOLATED")

        klines = _klines([
            ("2024-01-01T00:00:00Z", "42000", "42000", "42000", "42000"),
            ("2024-01-01T01:00:00Z", "42000", "42000", "42000", "42000"),  # 成交 @42021
            ("2024-01-01T02:00:00Z", "38500", "38600", "37900", "38000"),  # 插针触发强平
            ("2024-01-01T03:00:00Z", "38000", "38100", "37950", "38050"),
        ])
        out = _loop().run(on_bar, _ctx(), klines)

        liq_trades = [t for t in out["trades"] if t.get("liquidation")]
        assert len(liq_trades) == 1
        lt = liq_trades[0]
        assert lt["side"] == "sell" and lt["position_effect"] == "CLOSE_LONG"
        assert D(lt["price"]) == LIQ_REF  # 非跳空:参考价成交
        assert lt["time"] == "2024-01-01T02:00:00Z"
        assert any(w.startswith("强平于 2024-01-01T02:00:00Z：LONG") for w in out["warnings"])
        # 强平后 flat:margin_used=0;CLOSE 单被拒(flat)
        assert D(out["equity_curve"][-1]["margin_used"]) == 0
        assert any("CLOSE_OVER_POSITION" in w for w in out["warnings"])
        # equity = cash = 99991.5958 + gross(−401.205527638) − liq fee(7.60178894)
        liq_fee = D("7.60178894")
        gross = (LIQ_REF - FILL_PX) * D("0.1")
        assert D(out["equity_curve"][-1]["equity"]) == D("99991.5958") + gross - liq_fee

    def test_funding_replay_settles_on_grid_bar(self):
        periods = [
            FundingPeriod(funding_time=parse_instant("2024-01-01T08:00:00Z"), settled_rate=D("0.0001"),
                          interval_seconds=28800, mark_price=None),
        ]
        state = {"placed": False}

        def on_bar(bar, ctx):
            if not state["placed"]:
                ctx.place_order(position_effect="OPEN_LONG", order_type="MARKET", amount=D("0.1"),
                                leverage=10, margin_mode="ISOLATED")
                state["placed"] = True

        # 9 根 1h bar(00:00..08:00):08:00 期归 bar 07:00((07:00, 08:00] 左开右闭)
        klines = _flat_klines(9)
        out = _loop(funding=periods).run(on_bar, _ctx(), klines)

        # f = −0.0001×42000×0.1 = −0.42(ISOLATED:cash 与 margin 同减)
        # 资金费结算统计不再进 warnings(spec §8:与快照 fundingPeriods/报告累计资金费同源,
        # 纯信息项不混入风险警示)——断言走 equity_curve funding_cum
        assert not any("funding settled" in w for w in out["warnings"])
        last = out["equity_curve"][-1]
        assert D(last["funding_cum"]) == D("-0.42000000")
        # margin = 420.21 − 0.42;equity = cash(99991.5958−0.42) + unrealized(−2.1)
        assert D(last["margin_used"]) == IM - D("0.42000000")
        assert D(last["equity"]) == D("99991.5958") - D("0.42") - D("2.1")
        # 07:00 之前的行 funding_cum 仍 0
        assert D(out["equity_curve"][6]["funding_cum"]) == 0

    def test_funding_skips_flat_periods(self):
        # 期次在开仓成交(bar1)之前归属 bar0(flat)→ 跳过不收费
        periods = [
            FundingPeriod(funding_time=parse_instant("2024-01-01T01:00:00Z"), settled_rate=D("0.0001"),
                          interval_seconds=3600, mark_price=None),
        ]
        state = {"placed": False}

        def on_bar(bar, ctx):
            if not state["placed"]:
                ctx.place_order(position_effect="OPEN_LONG", order_type="MARKET", amount=D("0.1"),
                                leverage=10, margin_mode="ISOLATED")
                state["placed"] = True

        out = _loop(funding=periods).run(on_bar, _ctx(), _flat_klines(3))
        # bar0 (00:00,01:00] 含 01:00 期,彼时 flat → 0 结算
        assert not any("funding settled" in w for w in out["warnings"])
        assert all(D(e["funding_cum"]) == 0 for e in out["equity_curve"])


class TestSpotOutputUnchanged:
    def test_spot_section8_has_no_perp_keys(self):
        # SPOT 输出与引入 PERP 前逐字节一致:无 market_type/liquidation_model/trade 条件字段
        ctx = BacktestContext(MagicMock(), task_id=1, symbol="BTC/USDT")
        state = {"placed": False}

        def on_bar(bar, ctx):
            if not state["placed"]:
                ctx.place_order(side="BUY", order_type="MARKET", amount=D("0.1"))
                state["placed"] = True

        loop = BacktestEventLoop(initial_capital=D("10000"), symbol="BTC/USDT", timeframe="1h")
        out = loop.run(on_bar, ctx, _flat_klines(3))
        assert "market_type" not in out and "liquidation_model" not in out
        assert set(out["trades"][0]) == {"time", "side", "price", "amount", "fee"}
        assert set(out["equity_curve"][0]) == {"time", "equity"}
