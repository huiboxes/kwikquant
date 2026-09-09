"""PerpLedger / FundingReplay 单测 — docs/perp-backtest-spec.md §3-§5 语义矩阵。

期望值全部手算(钱数学委托 perp_math 内核,内核正确性由 tests/fixtures/perp 双侧对拍锁定,
本层只验证编排:分解/闸门/强平近似/资金费入账)。金额比较用 Decimal == (数值语义)。
"""

from __future__ import annotations

from datetime import datetime, timezone
from decimal import Decimal

import pytest

from kwikquant_worker.backtest.perp_ledger import (
    FundingPeriod,
    FundingReplay,
    PerpLedger,
    norm,
    parse_instant,
    timeframe_seconds,
)

D = Decimal


def make_ledger(capital: str = "100000", taker: str = "0.002") -> PerpLedger:
    return PerpLedger(initial_capital=D(capital), taker_fee_rate=D(taker))


def open_long(led: PerpLedger, qty: str, price: str, lev: int = 10, mode: str = "ISOLATED", fee: str | None = None):
    led.apply_fill(
        position_effect="OPEN_LONG",
        fill_qty=D(qty),
        fill_price=D(price),
        fee=D(fee) if fee is not None else D(price) * D(qty) * D("0.002"),
        leverage=lev,
        margin_mode=mode,
    )


class TestHelpers:
    def test_norm_negative_zero(self):
        # P2-9:全平 margin_delta 可为 Decimal("-0"),str 输出 "-0" —— 必须规范化
        assert str(norm(Decimal("-0"))) == "0"
        assert str(norm(D("0.000"))) == "0"
        assert str(norm(D("-1.5"))) == "-1.5"

    @pytest.mark.parametrize(
        "tf,sec", [("1m", 60), ("5m", 300), ("15m", 900), ("1h", 3600), ("4h", 14400), ("1d", 86400)]
    )
    def test_timeframe_seconds(self, tf, sec):
        assert timeframe_seconds(tf) == sec

    @pytest.mark.parametrize("tf", ["", "h", "0h", "-1m", "1x", "abc", None, "1M"])
    def test_timeframe_seconds_invalid(self, tf):
        # "1M"(ccxt 月线)不得折叠成分钟线:大小写敏感 fail-closed
        with pytest.raises(ValueError, match="unsupported timeframe"):
            timeframe_seconds(tf)

    @pytest.mark.parametrize("tf", ["1w", "2d", "48h"])
    def test_timeframe_seconds_beyond_lookahead_buffer_rejected(self, tf):
        # 归属窗超 24h 前瞻缓冲 → 静默漏收期次,fail-closed 拒
        with pytest.raises(ValueError, match="look-ahead buffer"):
            timeframe_seconds(tf)

    def test_parse_instant_z_and_offset(self):
        assert parse_instant("2024-01-01T08:00:00Z") == datetime(2024, 1, 1, 8, tzinfo=timezone.utc)
        assert parse_instant("2024-01-01T08:00:00+00:00").tzinfo is not None

    def test_parse_instant_rejects_naive_and_garbage(self):
        with pytest.raises(ValueError, match="without timezone"):
            parse_instant("2024-01-01T08:00:00")
        with pytest.raises(ValueError, match="invalid timestamp"):
            parse_instant("not-a-time")


class TestOpenClose:
    def test_long_open_add_partial_close_full_close_chain(self):
        """A→B→C→D 链式场景(spec §3.1 守恒):开仓只锁 margin 不动 cash,fee 逐笔扣。"""
        led = make_ledger()
        # A: OPEN_LONG 0.1 @ 42000 lev10 → im=420, fee=8.4
        open_long(led, "0.1", "42000", fee="8.4")
        assert led.pos.signed_qty == D("0.1")
        assert led.pos.avg_price == D("42000")
        assert led.pos.margin == D("420.00000000")
        assert led.cash == D("99991.6")
        assert led.available() == D("99571.6")
        assert led.equity(D("42000")) == D("99991.6")  # unrealized=0
        assert led.position_side() == "LONG"
        # B: 加仓 0.1 @ 43000 → 加权 42500, margin=850, fee=8.6
        open_long(led, "0.1", "43000", fee="8.6")
        assert led.pos.avg_price == D("42500.00000000")
        assert led.pos.margin == D("850.00000000")
        assert led.cash == D("99983.0")
        assert led.unrealized(D("43000")) == D("100.0")  # (43000−42500)×0.2
        assert led.equity(D("43000")) == D("100083.0")
        # C: 部分平 0.05 @ 43000 → pnl=25, release=212.5(按比例), fee=4.3
        led.apply_fill(
            position_effect="CLOSE_LONG", fill_qty=D("0.05"), fill_price=D("43000"), fee=D("4.3"),
            leverage=None, margin_mode=None,
        )
        assert led.pos.signed_qty == D("0.15")
        assert led.pos.avg_price == D("42500.00000000")  # 减仓均价不变
        assert led.pos.margin == D("637.50000000")
        assert led.cash == D("100003.7")  # 99983 + 25 − 4.3
        # D: 全平 0.15 @ 43000 → pnl=75, release=637.5(全平精确), fee=12.9
        led.apply_fill(
            position_effect="CLOSE_LONG", fill_qty=D("0.15"), fill_price=D("43000"), fee=D("12.9"),
            leverage=None, margin_mode=None,
        )
        assert led.is_flat()
        assert led.pos.margin == D("0")
        assert led.cash == D("100065.8")  # 100003.7 + 75 − 12.9
        # 守恒:净利 = 毛利 100 − fees 34.2 = 65.8
        assert led.realized_pnl == D("65.8")
        assert led.equity(D("43000")) == led.cash

    def test_short_open_close_mirror(self):
        led = make_ledger()
        led.apply_fill(
            position_effect="OPEN_SHORT", fill_qty=D("0.1"), fill_price=D("42000"), fee=D("8.4"),
            leverage=10, margin_mode="ISOLATED",
        )
        assert led.pos.signed_qty == D("-0.1")
        assert led.position_side() == "SHORT"
        assert led.pos.margin == D("420.00000000")
        # 空头盈利:价格下跌
        assert led.unrealized(D("41000")) == D("100.0")
        led.apply_fill(
            position_effect="CLOSE_SHORT", fill_qty=D("0.1"), fill_price=D("41000"), fee=D("8.2"),
            leverage=None, margin_mode=None,
        )
        assert led.is_flat()
        assert led.cash == D("100000") - D("8.4") + D("100") - D("8.2")  # 100083.4

    def test_open_short_adds_to_short(self):
        led = make_ledger()
        led.apply_fill(
            position_effect="OPEN_SHORT", fill_qty=D("0.1"), fill_price=D("42000"), fee=D("8.4"),
            leverage=10, margin_mode="ISOLATED",
        )
        # CLOSE_LONG 语义在空头仓 = 减仓;OPEN_SHORT 同号 = 加仓
        led.apply_fill(
            position_effect="OPEN_SHORT", fill_qty=D("0.1"), fill_price=D("41000"), fee=D("8.2"),
            leverage=None, margin_mode=None,
        )
        assert led.pos.signed_qty == D("-0.2")
        assert led.pos.avg_price == D("41500.00000000")
        assert led.pos.margin == D("420.00000000") + D("410.00000000")


class TestGate:
    def test_close_over_position_rejected(self):
        led = make_ledger()
        open_long(led, "0.1", "42000", fee="8.4")
        # 超量平
        r = led.gate(position_effect="CLOSE_LONG", fill_qty=D("0.2"), fill_price=D("42000"),
                     fee=D("1"), leverage=None, margin_mode=None)
        assert r is not None and "CLOSE_OVER_POSITION" in r
        # flat 时 CLOSE
        flat = make_ledger()
        r = flat.gate(position_effect="CLOSE_LONG", fill_qty=D("0.1"), fill_price=D("42000"),
                      fee=D("1"), leverage=None, margin_mode=None)
        assert r is not None and "CLOSE_OVER_POSITION" in r
        # 方向矛盾:LONG 仓收 CLOSE_SHORT(平空但无空头)
        r = led.gate(position_effect="CLOSE_SHORT", fill_qty=D("0.05"), fill_price=D("42000"),
                     fee=D("1"), leverage=None, margin_mode=None)
        assert r is not None and "CLOSE_OVER_POSITION" in r

    def test_reduce_by_open_opposite_within_position_allowed(self):
        # OPEN_SHORT 对 LONG 仓 |d| ≤ |q| = 减仓(§3.3),纯减仓无现金闸门
        led = make_ledger(capital="500")  # cash 很少也应放行减仓
        open_long(led, "0.1", "42000", fee="8.4")
        assert led.gate(position_effect="OPEN_SHORT", fill_qty=D("0.05"), fill_price=D("42000"),
                        fee=D("4.2"), leverage=None, margin_mode=None) is None

    def test_leverage_margin_mode_mismatch_rejected(self):
        led = make_ledger()
        open_long(led, "0.1", "42000", lev=10, fee="8.4")
        r = led.gate(position_effect="OPEN_LONG", fill_qty=D("0.01"), fill_price=D("42000"),
                     fee=D("1"), leverage=20, margin_mode=None)
        assert r is not None and "LEVERAGE_MISMATCH" in r
        r = led.gate(position_effect="OPEN_LONG", fill_qty=D("0.01"), fill_price=D("42000"),
                     fee=D("1"), leverage=None, margin_mode="CROSS")
        assert r is not None and "MARGIN_MODE_MISMATCH" in r
        # 缺省继承 → 放行
        assert led.gate(position_effect="OPEN_LONG", fill_qty=D("0.01"), fill_price=D("42000"),
                        fee=D("0.84"), leverage=None, margin_mode=None) is None

    def test_open_requires_leverage_and_margin_mode_on_flat(self):
        led = make_ledger()
        r = led.gate(position_effect="OPEN_LONG", fill_qty=D("0.1"), fill_price=D("42000"),
                     fee=D("8.4"), leverage=None, margin_mode=None)
        assert r is not None and "leverage and marginMode required" in r

    def test_insufficient_margin_rejected_isolated(self):
        # available = 400 < im(420)+fee(8.4) → 拒
        led = make_ledger(capital="400")
        r = led.gate(position_effect="OPEN_LONG", fill_qty=D("0.1"), fill_price=D("42000"),
                     fee=D("8.4"), leverage=10, margin_mode="ISOLATED")
        assert r is not None and "insufficient margin" in r
        # 足额 → 放行(1000 ≥ 420+8.4)
        led2 = make_ledger(capital="1000")
        assert led2.gate(position_effect="OPEN_LONG", fill_qty=D("0.1"), fill_price=D("42000"),
                         fee=D("8.4"), leverage=10, margin_mode="ISOLATED") is None

    def test_margin_depleted_rejects_everything(self):
        # 穿蚀仓(spec §3.3):margin < 0 → CLOSE/OPEN 全拒,只能等强平退出
        led = make_ledger()
        open_long(led, "0.1", "42000", fee="8.4")
        for _ in range(3):
            led.settle_funding_period(D("0.05"), D("42000"))  # margin 420 → −210
        assert led.pos.margin < 0
        r = led.gate(position_effect="CLOSE_LONG", fill_qty=D("0.1"), fill_price=D("42000"),
                     fee=D("8.4"), leverage=None, margin_mode=None)
        assert r is not None and "MARGIN_DEPLETED" in r
        r = led.gate(position_effect="OPEN_LONG", fill_qty=D("0.01"), fill_price=D("42000"),
                     fee=D("1"), leverage=None, margin_mode=None)
        assert r is not None and "MARGIN_DEPLETED" in r

    def test_existing_isolated_margin_counts_against_reopen(self):
        # 锁定式:存量仓保证金计入 available,防"同一笔钱反复开仓"(与 paper D4 风控语义一致)
        led = make_ledger(capital="500")
        open_long(led, "0.1", "42000", lev=10, fee="8.4")  # margin 420, available ≈ 71.6
        r = led.gate(position_effect="OPEN_LONG", fill_qty=D("0.1"), fill_price=D("42000"),
                     fee=D("8.4"), leverage=None, margin_mode=None)
        assert r is not None and "insufficient margin" in r

    def test_flip_gate_uses_net_effect(self):
        # 反转 = CLOSE 段先回笼(pnl+release),OPEN 段再占:净闸门放行"亏损但保证金足"的反转
        led = make_ledger(capital="1000")
        open_long(led, "0.1", "42000", lev=10, fee="8.4")  # margin 420, cash 991.6
        # OPEN_SHORT 0.15 @ 42000 → CLOSE 0.1(pnl=0, release=420)+ OPEN SHORT 0.05(im=210)
        # predicted: cash' = 991.6 − fee(0.15×42000×0.002=12.6) + 0 = 979; margin' = 420−420+210 = 210
        # available' = 769 ≥ 0 → 放行
        assert led.gate(position_effect="OPEN_SHORT", fill_qty=D("0.15"), fill_price=D("42000"),
                        fee=D("12.6"), leverage=None, margin_mode=None) is None
        # 巨额反转(OPEN_SHORT 1.0):im(0.9)=3780 > 回笼后现金 → 拒
        r = led.gate(position_effect="OPEN_SHORT", fill_qty=D("1.0"), fill_price=D("42000"),
                     fee=D("84"), leverage=None, margin_mode=None)
        assert r is not None and "insufficient margin" in r


class TestCross:
    def test_cross_margin_stays_zero(self):
        led = make_ledger()
        open_long(led, "0.1", "42000", mode="CROSS", fee="8.4")
        assert led.pos.margin == D("0")  # 账户担保不划转(spec §3.1)
        assert led.available() == led.cash
        # gate 仍要求 cash 覆盖 im + fee
        tight = make_ledger(capital="400")
        r = tight.gate(position_effect="OPEN_LONG", fill_qty=D("0.1"), fill_price=D("42000"),
                       fee=D("8.4"), leverage=10, margin_mode="CROSS")
        assert r is not None and "insufficient margin" in r
        # 平仓入 cash
        led.apply_fill(position_effect="CLOSE_LONG", fill_qty=D("0.1"), fill_price=D("43000"),
                       fee=D("8.6"), leverage=None, margin_mode=None)
        assert led.is_flat()
        assert led.cash == D("100000") - D("8.4") + D("100") - D("8.6")


class TestDecompose:
    def test_segments(self):
        led = make_ledger()
        assert led.decompose("OPEN_LONG", D("0.1")) == [(True, "LONG", D("0.1"))]  # flat 开仓
        open_long(led, "0.5", "42000", fee="42")
        assert led.decompose("OPEN_LONG", D("0.2")) == [(True, "LONG", D("0.2"))]  # 同号加仓
        assert led.decompose("OPEN_SHORT", D("0.3")) == [(False, "LONG", D("0.3"))]  # 异号减仓
        assert led.decompose("CLOSE_LONG", D("0.3")) == [(False, "LONG", D("0.3"))]
        # 等量边界(|d| == |q|,decompose 的 <= 分支):单 CLOSE 段收尾,不得产出零量 OPEN 段
        # (<= 回归成 < 时此处会拆出 (True,"SHORT",0) 段 → 内核 requirePositive 拒,整任务失败)
        assert led.decompose("OPEN_SHORT", D("0.5")) == [(False, "LONG", D("0.5"))]
        # 穿零反转:全平 0.5 + 反开 0.3
        assert led.decompose("OPEN_SHORT", D("0.8")) == [
            (False, "LONG", D("0.5")),
            (True, "SHORT", D("0.3")),
        ]

    def test_flip_applies_two_segments_one_trade(self):
        led = make_ledger()
        open_long(led, "0.2", "42500", lev=10, fee="17")  # margin 850, cash 99983
        # E 场景:OPEN_SHORT 0.3 @ 42000 → CLOSE 0.2(pnl=−100,release=850)+ OPEN SHORT 0.1(im=420)
        led.apply_fill(position_effect="OPEN_SHORT", fill_qty=D("0.3"), fill_price=D("42000"),
                       fee=D("25.2"), leverage=None, margin_mode=None)
        assert led.pos.signed_qty == D("-0.1")
        assert led.pos.avg_price == D("42000")
        assert led.pos.margin == D("420.00000000")
        assert led.cash == D("99983") - D("100") - D("25.2")  # 99857.8


class TestLiquidation:
    def _long_ledger(self, capital="100000"):
        led = make_ledger(capital)
        open_long(led, "0.1", "42000", lev=10, fee="8.4")  # margin=420, liq ref=37989.94974874
        return led

    def test_no_trigger_above_liq_price(self):
        led = self._long_ledger()
        # low 38000 > liq 37989.95 → 不触发
        assert led.check_liquidation(timestamp="t", open_=D("38500"), high=D("38800"), low=D("38000")) is None

    def test_isolated_long_wick_fills_at_reference_price(self):
        led = self._long_ledger()
        # low 37900 触发,open 38500 未破 → 按参考价成交(F 场景)
        ev = led.check_liquidation(timestamp="t1", open_=D("38500"), high=D("38600"), low=D("37900"))
        assert ev is not None
        assert ev.price == D("37989.94974874")  # liquidation_price_isolated(42000, 0.1, 420, 0.005)
        assert ev.position_side == "LONG" and ev.qty == D("0.1")
        # fee = ref×0.1×0.002 = 7.597989949748 → 8 位 HALF_UP;gross = (ref−42000)×0.1(closed_pnl EXACT 不舍入)
        assert ev.fee == D("7.59798995")
        assert ev.gross_pnl == D("-401.005025126")
        assert led.is_flat() and led.pos.margin == D("0")
        assert led.cash == D("99991.6") + ev.gross_pnl - ev.fee
        assert led.liquidations == [ev]

    def test_gap_through_open_fills_at_open(self):
        led = self._long_ledger()
        # open 37000 已破(margin_balance(open) = 420−500 = −80 ≤ 0)→ 按 open 成交(G 场景)
        ev = led.check_liquidation(timestamp="t1", open_=D("37000"), high=D("37500"), low=D("36900"))
        assert ev is not None and ev.price == D("37000")
        assert ev.gross_pnl == D("-500.0")  # (37000−42000)×0.1

    def test_short_mirror_triggers_on_high(self):
        led = make_ledger()
        led.apply_fill(position_effect="OPEN_SHORT", fill_qty=D("0.1"), fill_price=D("42000"),
                       fee=D("8.4"), leverage=10, margin_mode="ISOLATED")
        # liq ref SHORT = (4200+420)/(0.1×1.005) = 46200/0.1005 = 45970.14925373
        assert led.liquidation_reference_price() == D("45970.14925373")
        assert led.check_liquidation(timestamp="t", open_=D("45000"), high=D("45900"), low=D("44800")) is None
        ev = led.check_liquidation(timestamp="t", open_=D("45000"), high=D("46100"), low=D("44800"))
        assert ev is not None and ev.position_side == "SHORT"
        assert ev.price == D("45970.14925373")

    def test_funding_eroded_margin_triggers_without_price_drop(self):
        # H 场景:资金费把 margin 侵蚀为负 → 价格不动也触发(与 paper D1 marginBreached 谓词一致)
        led = self._long_ledger()
        # 三期正费率 LONG 付:f = −0.05×42000×0.1 = −210/期 → margin 420 − 630 = −210
        for _ in range(3):
            led.settle_funding_period(D("0.05"), D("42000"))
        assert led.pos.margin == D("-210.00000000")
        ev = led.check_liquidation(timestamp="t", open_=D("42000"), high=D("42100"), low=D("41900"))
        assert ev is not None
        # open 处已 breach(margin_balance = −210 + 0 ≤ 0)→ 跳空口径按 open 成交
        assert ev.price == D("42000")

    def test_cross_liquidation_fills_at_extreme(self):
        # I 场景:CROSS 账户级,无参考价 → 触发极值成交;穿仓 cash 可为负
        led = make_ledger(capital="150")
        open_long(led, "0.1", "42000", mode="CROSS", fee="0")
        assert led.check_liquidation(timestamp="t", open_=D("41500"), high=D("41600"), low=D("41000")) is None
        ev = led.check_liquidation(timestamp="t", open_=D("41500"), high=D("41600"), low=D("40500"))
        assert ev is not None
        assert ev.price == D("40500")  # 触发极值(保守近似,spec §4.1 规则 3)
        assert ev.margin_mode == "CROSS"
        assert led.cash == D("150") - D("150") - ev.fee  # 全额穿仓从 cash 扣穿
        assert led.cash < 0

    def test_flat_never_liquidates(self):
        led = make_ledger()
        assert led.check_liquidation(timestamp="t", open_=D("1"), high=D("2"), low=D("0.5")) is None

    def test_liquidation_reference_price_cross_and_flat_none(self):
        led = make_ledger()
        assert led.liquidation_reference_price() is None  # flat
        open_long(led, "0.1", "42000", mode="CROSS", fee="8.4")
        assert led.liquidation_reference_price() is None  # CROSS 无单一参考价(与 paper 口径一致)


class TestFunding:
    def test_isolated_pays_erodes_margin_and_cash(self):
        led = make_ledger()
        open_long(led, "0.1", "42000", fee="8.4")  # cash 99991.6, margin 420
        # J 场景:LONG 付正费率:f = 0.0001×42000×0.1×(−1) = −0.42
        f = led.settle_funding_period(D("0.0001"), D("42000"))
        assert f == D("-0.42000000")
        assert led.cash == D("99991.18")
        assert led.pos.margin == D("419.58000000")
        assert led.available() == D("99571.6")  # cash 与 margin 同减,available 不变(spec §5.4)
        assert led.funding_cum == D("-0.42000000") and led.funding_periods_settled == 1

    def test_isolated_receives_thickens_margin(self):
        led = make_ledger()
        led.apply_fill(position_effect="OPEN_SHORT", fill_qty=D("0.1"), fill_price=D("42000"),
                       fee=D("8.4"), leverage=10, margin_mode="ISOLATED")
        f = led.settle_funding_period(D("0.0001"), D("42000"))  # SHORT 收正费率
        assert f == D("0.42000000")
        assert led.pos.margin == D("420.42000000")

    def test_cross_funding_cash_only(self):
        led = make_ledger()
        open_long(led, "0.1", "42000", mode="CROSS", fee="8.4")
        led.settle_funding_period(D("0.0001"), D("42000"))
        assert led.pos.margin == D("0")  # 恒 0
        assert led.cash == D("99991.6") - D("0.42")

    def test_flat_period_skipped(self):
        led = make_ledger()
        assert led.settle_funding_period(D("0.0001"), D("42000")) == D("0")
        assert led.funding_periods_settled == 0 and led.funding_cum == D("0")

    def test_margin_can_go_negative(self):
        led = make_ledger()
        open_long(led, "0.1", "42000", fee="8.4")
        led.settle_funding_period(D("-0.02"), D("42000"))  # f = +84? 负费率 LONG 收
        # 负费率 LONG:side_sign=−1 × rate(−0.02) → f = −0.02×42000×0.1×(−1) = +84(收)
        assert led.pos.margin == D("504.00000000")
        led.settle_funding_period(D("0.02"), D("42000"))  # 正费率 LONG 付 −84
        led.settle_funding_period(D("0.02"), D("42000"))
        led.settle_funding_period(D("0.02"), D("42000"))
        led.settle_funding_period(D("0.02"), D("42000"))
        led.settle_funding_period(D("0.02"), D("42000"))
        led.settle_funding_period(D("0.02"), D("42000"))  # 6 次付 84 → 504 − 504 = 0,再穿
        assert led.pos.margin == D("0")
        led.settle_funding_period(D("0.02"), D("42000"))
        assert led.pos.margin == D("-84.00000000")  # 可负(穿蚀,下一 bar 强平谓词立即触发)


def _p(ts: str, rate: str) -> FundingPeriod:
    return FundingPeriod(
        funding_time=parse_instant(ts), settled_rate=D(rate), interval_seconds=28800, mark_price=None
    )


class TestFundingReplay:
    PERIODS = [
        _p("2024-01-01T08:00:00Z", "0.0001"),
        _p("2024-01-01T16:00:00Z", "0.0002"),
        _p("2024-01-02T00:00:00Z", "-0.0001"),
    ]

    def test_left_open_right_closed(self):
        r = FundingReplay(self.PERIODS, "1h")
        # bar 07:00 → (07:00, 08:00] 含 08:00 期
        got = r.periods_for_bar("2024-01-01T07:00:00Z")
        assert [str(p.settled_rate) for p in got] == ["0.0001"]
        # bar 08:00 → (08:00, 09:00] 不含 08:00(归上一根)
        assert r.periods_for_bar("2024-01-01T08:00:00Z") == []
        # bar 15:00 → 16:00 期
        assert r.periods_for_bar("2024-01-01T15:00:00Z") == [self.PERIODS[1]]

    def test_multiple_periods_in_one_bar(self):
        r = FundingReplay(self.PERIODS, "1d")
        # 1d bar 00:00 → (00:00, 次日 00:00] 含三期
        got = r.periods_for_bar("2024-01-01T00:00:00Z")
        assert len(got) == 3
        assert r.pending_count == 0

    def test_cursor_monotonic_no_double_settle(self):
        r = FundingReplay(self.PERIODS, "1h")
        assert len(r.periods_for_bar("2024-01-01T23:00:00Z")) == 3  # 一根 bar 追平全部
        assert r.periods_for_bar("2024-01-02T00:00:00Z") == []  # 已消费不重复
        # 时间轴倒退(防御):游标不回退,不重复收费
        assert r.periods_for_bar("2024-01-01T07:00:00Z") == []

    def test_empty_series(self):
        r = FundingReplay([], "1h")
        assert r.periods_for_bar("2024-01-01T00:00:00Z") == []
