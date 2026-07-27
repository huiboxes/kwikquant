package com.kwikquant.report.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PerformanceCalculatorTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final BigDecimal RISK_FREE = new BigDecimal("0.02");

    // ---- happy path ----

    @Test
    void happyPath_twoCompletePairs_allMetricsCalculated() {
        // 4 trades: buy@100 qty=10, sell@110 qty=10, buy@105 qty=10, sell@108 qty=10
        // Pair1 PnL = 110*10 - 100*10 - 1 - 1 = 98
        // Pair2 PnL = 108*10 - 105*10 - 1 - 1 = 28
        List<TradeRecord> trades = List.of(
                trade("buy", T0, "100", "10", "1"),
                trade("sell", T0.plus(1, ChronoUnit.DAYS), "110", "10", "1"),
                trade("buy", T0.plus(2, ChronoUnit.DAYS), "105", "10", "1"),
                trade("sell", T0.plus(3, ChronoUnit.DAYS), "108", "10", "1"));

        // 5 equity points spanning 4 days
        List<EquityPoint> equityCurve = List.of(
                new EquityPoint(T0, new BigDecimal("10000")),
                new EquityPoint(T0.plus(1, ChronoUnit.DAYS), new BigDecimal("10500")),
                new EquityPoint(T0.plus(2, ChronoUnit.DAYS), new BigDecimal("10200")),
                new EquityPoint(T0.plus(3, ChronoUnit.DAYS), new BigDecimal("10800")),
                new EquityPoint(T0.plus(4, ChronoUnit.DAYS), new BigDecimal("10900")));

        PerformanceMetrics m = PerformanceCalculator.calculate(trades, equityCurve, RISK_FREE);

        assertThat(m.totalTrades()).isEqualTo(2);

        // totalReturn from equity curve: (10900 - 10000) / 10000 = 0.09
        assertThat(m.totalReturn()).isEqualByComparingTo(new BigDecimal("0.09"));

        // winRate: 2 wins / 2 total = 1.0
        assertThat(m.winRate()).isEqualByComparingTo(BigDecimal.ONE);

        // profitFactor: null (no losing trades)
        assertThat(m.profitFactor()).isNull();

        // maxDrawdown: peak=10500, trough=10200 -> (10500-10200)/10500 ~= 0.02857
        assertThat(m.maxDrawdown())
                .isNotNull()
                .isCloseTo(new BigDecimal("0.02857143"), within(new BigDecimal("0.0001")));

        // sharpeRatio: non-null, should be a positive value given positive returns
        assertThat(m.sharpeRatio()).isNotNull();
        assertThat(m.sharpeRatio().signum()).isGreaterThan(0);

        // avgTradeDurationSeconds: pair1 = 1 day, pair2 = 1 day, avg = 86400
        assertThat(m.avgTradeDurationSeconds()).isEqualTo(86400L);
    }

    // ---- empty equity curve ----

    @Test
    void emptyEquityCurve_totalReturnFromTrades() {
        // Same 4 trades as happy path, but no equity curve
        List<TradeRecord> trades = List.of(
                trade("buy", T0, "100", "10", "1"),
                trade("sell", T0.plus(1, ChronoUnit.DAYS), "110", "10", "1"),
                trade("buy", T0.plus(2, ChronoUnit.DAYS), "105", "10", "1"),
                trade("sell", T0.plus(3, ChronoUnit.DAYS), "108", "10", "1"));

        PerformanceMetrics m = PerformanceCalculator.calculate(trades, null, RISK_FREE);

        // totalReturn from trades: initialCapital = 100*10 = 1000
        // totalPnl = 98 + 28 = 126
        // totalReturn = 126 / 1000 = 0.126
        assertThat(m.totalReturn()).isEqualByComparingTo(new BigDecimal("0.126"));

        // sharpeRatio and maxDrawdown null without equity curve
        assertThat(m.sharpeRatio()).isNull();
        assertThat(m.maxDrawdown()).isNull();

        assertThat(m.totalTrades()).isEqualTo(2);
        assertThat(m.winRate()).isEqualByComparingTo(BigDecimal.ONE);
    }

    // ---- only buys ----

    @Test
    void onlyBuys_noSellPairs_metricsAreDefault() {
        List<TradeRecord> trades = List.of(
                trade("buy", T0, "100", "10", "1"),
                trade("buy", T0.plus(1, ChronoUnit.HOURS), "105", "5", "0.5"),
                trade("buy", T0.plus(2, ChronoUnit.HOURS), "103", "8", "0.8"));

        PerformanceMetrics m = PerformanceCalculator.calculate(trades, null, RISK_FREE);

        assertThat(m.totalTrades()).isEqualTo(0);
        assertThat(m.totalReturn()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(m.winRate()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(m.profitFactor()).isNull();
        assertThat(m.sharpeRatio()).isNull();
        assertThat(m.maxDrawdown()).isNull();
        assertThat(m.avgTradeDurationSeconds()).isEqualTo(0L);
    }

    // ---- all winning ----

    @Test
    void allWinning_profitFactorIsNull() {
        // Two profitable pairs: buy@100 sell@120, buy@90 sell@95
        List<TradeRecord> trades = List.of(
                trade("buy", T0, "100", "10", "0"),
                trade("sell", T0.plus(1, ChronoUnit.DAYS), "120", "10", "0"),
                trade("buy", T0.plus(2, ChronoUnit.DAYS), "90", "10", "0"),
                trade("sell", T0.plus(3, ChronoUnit.DAYS), "95", "10", "0"));

        PerformanceMetrics m = PerformanceCalculator.calculate(trades, null, RISK_FREE);

        assertThat(m.totalTrades()).isEqualTo(2);
        assertThat(m.winRate()).isEqualByComparingTo(BigDecimal.ONE);

        // profitFactor null because totalLoss = 0
        assertThat(m.profitFactor()).isNull();
    }

    // ---- all losing ----

    @Test
    void allLosing_winRateIsZero() {
        // Two losing pairs: buy@100 sell@90, buy@80 sell@70
        List<TradeRecord> trades = List.of(
                trade("buy", T0, "100", "10", "0"),
                trade("sell", T0.plus(1, ChronoUnit.DAYS), "90", "10", "0"),
                trade("buy", T0.plus(2, ChronoUnit.DAYS), "80", "10", "0"),
                trade("sell", T0.plus(3, ChronoUnit.DAYS), "70", "10", "0"));

        PerformanceMetrics m = PerformanceCalculator.calculate(trades, null, RISK_FREE);

        assertThat(m.totalTrades()).isEqualTo(2);
        assertThat(m.winRate()).isEqualByComparingTo(BigDecimal.ZERO);

        // profitFactor: totalProfit=0, totalLoss>0 -> 0/totalLoss = 0
        assertThat(m.profitFactor()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ---- single equity point ----

    @Test
    void singleEquityPoint_sharpeRatioAndMaxDrawdownAreNull() {
        List<TradeRecord> trades = List.of(
                trade("buy", T0, "100", "10", "1"), trade("sell", T0.plus(1, ChronoUnit.DAYS), "110", "10", "1"));

        List<EquityPoint> equityCurve = List.of(new EquityPoint(T0, new BigDecimal("10000")));

        PerformanceMetrics m = PerformanceCalculator.calculate(trades, equityCurve, RISK_FREE);

        assertThat(m.sharpeRatio()).isNull();
        assertThat(m.maxDrawdown()).isNull();

        // totalReturn falls back to trade-based calculation since curve < 2 points
        // initialCapital = 100*10 = 1000, pnl = 110*10 - 100*10 - 1 - 1 = 98
        // totalReturn = 98 / 1000 = 0.098
        assertThat(m.totalReturn()).isEqualByComparingTo(new BigDecimal("0.098"));
        assertThat(m.totalTrades()).isEqualTo(1);
    }

    // ---- fee null ----

    @Test
    void feeIsNull_treatedAsZero() {
        // Trade with fee=null should not throw NPE
        List<TradeRecord> trades = List.of(
                trade("buy", T0, "100", "10", null), trade("sell", T0.plus(1, ChronoUnit.DAYS), "110", "10", null));

        PerformanceMetrics m = PerformanceCalculator.calculate(trades, null, RISK_FREE);

        // PnL = 110*10 - 100*10 - 0 - 0 = 100
        // totalReturn = 100 / (100*10) = 0.1
        assertThat(m.totalReturn()).isEqualByComparingTo(new BigDecimal("0.1"));
        assertThat(m.totalTrades()).isEqualTo(1);
        assertThat(m.winRate()).isEqualByComparingTo(BigDecimal.ONE);
    }

    // ---- H6 regression: quantity-based FIFO must not drop notional on partial fills ----

    @Test
    void onePartialBuy_twoPartialSells_bothSegmentsAccountedFor() {
        // buy 10 @ 100, then two partial sells of 5 each at different prices.
        // Segment1 pnl = 110*5 - 100*5 = 50; Segment2 pnl = 120*5 - 100*5 = 100; total = 150.
        List<TradeRecord> trades = List.of(
                trade("buy", T0, "100", "10", "0"),
                trade("sell", T0.plus(1, ChronoUnit.DAYS), "110", "5", "0"),
                trade("sell", T0.plus(2, ChronoUnit.DAYS), "120", "5", "0"));

        PerformanceMetrics m = PerformanceCalculator.calculate(trades, null, RISK_FREE);

        // Both partial sells must produce their own matched segment (not dropped).
        assertThat(m.totalTrades()).isEqualTo(2);
        assertThat(m.winRate()).isEqualByComparingTo(BigDecimal.ONE);
        // totalReturn = totalPnl / initialCapital = 150 / (100*10) = 0.15
        assertThat(m.totalReturn()).isEqualByComparingTo(new BigDecimal("0.15"));
        // avg duration = (1 day + 2 days) / 2 = 1.5 days = 129600s
        assertThat(m.avgTradeDurationSeconds()).isEqualTo(129_600L);
    }

    @Test
    void twoPartialBuys_oneSell_bothLotsMatchedProportionally() {
        // buy 5 @ 100 (t0), buy 5 @ 110 (t0+1h), sell 10 @ 130 (t0+2h).
        // FIFO: lot1(100,5) matched first -> pnl 150; lot2(110,5) matched next -> pnl 100; total 250.
        List<TradeRecord> trades = List.of(
                trade("buy", T0, "100", "5", "0"),
                trade("buy", T0.plus(1, ChronoUnit.HOURS), "110", "5", "0"),
                trade("sell", T0.plus(2, ChronoUnit.HOURS), "130", "10", "0"));

        PerformanceMetrics m = PerformanceCalculator.calculate(trades, null, RISK_FREE);

        assertThat(m.totalTrades()).isEqualTo(2);
        assertThat(m.winRate()).isEqualByComparingTo(BigDecimal.ONE);
        // totalReturn = totalPnl / initialCapital(first buy) = 250 / (100*5) = 0.5
        assertThat(m.totalReturn()).isEqualByComparingTo(new BigDecimal("0.5"));
        // avg duration = (2h + 1h) / 2 = 1.5h = 5400s
        assertThat(m.avgTradeDurationSeconds()).isEqualTo(5_400L);
    }

    // ---- regression: report id=2 极小方差 + 低波动约束 ----

    /**
     * 回归 report id=2:8760 根 1h bar、equity ~100000 小幅波动(±0.002),每日 return ~1e-8、
     * 方差 ~1e-16。旧码 {@code standardDeviation} 用 {@code SCALE=8}(小数位)divide 把方差
     * 舍入到 {@code 0E-8}=0 → stddev=0 → {@code calculateSharpeRatio} 误判 null。
     *
     * <p>修复后两层:① {@code STAT_MC}(有效数字)防方差舍入 0;② 年化 stddev<0.1% 低波动约束
     * 返 null(诚实:数据接近无波动,sharpe 无意义)。本场景方差 ~1e-16,stddev ~1e-8,
     * 年化 stddev ~1e-6 ≪ 0.1% → 走低波动约束返 null(不再爆出 ~-70 的误导值)。
     */
    @Test
    void tinyVariance_equityCurve_sharpeNullLowVolatility() {
        List<TradeRecord> trades = List.of(
                trade("buy", T0, "100", "0.001", "0"), trade("sell", T0.plus(1, ChronoUnit.DAYS), "101", "0.001", "0"));

        List<EquityPoint> curve = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            // 100000 上下 ±0.002 的极小波动,return ~1e-8,方差 ~1e-16(旧码 SCALE=8 → 舍入到 0)
            BigDecimal eq = new BigDecimal("100000").add(new BigDecimal(String.valueOf(((i % 5) - 2) * 0.001)));
            curve.add(new EquityPoint(T0.plus(i, ChronoUnit.HOURS), eq));
        }

        PerformanceMetrics m = PerformanceCalculator.calculate(trades, curve, RISK_FREE);

        // 极小波动(年化 stddev ≪ 0.1%)→ 低波动约束返 null,不再爆出误导值。
        assertThat(m.sharpeRatio()).as("极小波动 sharpe 无意义,低波动约束应返 null").isNull();
    }

    // ---- regression: 1h interval 年化倍数 bug + 低波动约束(sharpe 爆到 -939) ----

    /**
     * 回归用户实测:1h interval 1 年回测,总收益 -0.05%,sharpe 算出 -939.25(爆)。
     *
     * <p>根因两层:① {@code calculateSharpeRatio} 把 equity 相邻点 return 当 <b>daily</b> return,
     * 年化 stddev 用 {@code *sqrt(365)};实际间隔=bar interval(1h),1h 时年化倍数该
     * {@code *sqrt(8760)},差 {@code sqrt(24)≈4.9} 倍 → 低波动场景严重低估年化 stddev。
     * ② 低波动(年化 stddev<0.1%)时 sharpe 公式放大器把微小负偏爆成几百。
     *
     * <p>修复:① 按实际间隔算 {@code pointsPerYear=SECONDS_PER_YEAR/avgIntervalSeconds},
     * 年化 stddev={@code periodStdDev*sqrt(pointsPerYear)};② 年化 stddev<0.1% 返 null
     * (诚实:数据接近无波动,sharpe 无意义)。本 test 用 ±2 低波动(equity 100000±2,
     * 年化 stddev ~1.3e-4 < 1e-3)验低波动约束生效返 null,不再爆出几百。
     */
    @Test
    void hourlyInterval_1y_lowVolatility_returnsNullNotExploded() {
        List<TradeRecord> trades = List.of(
                trade("buy", T0, "100", "0.001", "0"),
                trade("sell", T0.plus(365, ChronoUnit.DAYS), "99.95", "0.001", "0"));

        int hours = 365 * 24;
        List<EquityPoint> curve = new ArrayList<>();
        for (int i = 0; i <= hours; i++) {
            double frac = (double) i / hours;
            double base = 100000 + (99950 - 100000) * frac;
            double wave = Math.sin(i * 0.1) * 2; // ±2 低波动
            curve.add(new EquityPoint(T0.plus(i, ChronoUnit.HOURS), new BigDecimal(String.valueOf(base + wave))));
        }

        PerformanceMetrics m = PerformanceCalculator.calculate(trades, curve, RISK_FREE);

        // 旧码 sharpe ~-758(年化倍数 *sqrt(365) 错 + 低波动放大);修复后年化 stddev<0.1%
        // → 诚实返 null,前端显"—"避免误导。
        assertThat(m.sharpeRatio())
                .as("低波动(年化 stddev<0.1%)sharpe 无意义,应返 null 而非爆出几百")
                .isNull();
    }

    /**
     * 中等波动场景(年化 stddev > 0.1%)sharpe 应非 null 且在合理范围(不爆)。
     * 1h interval 1 年 ±500 波动(equity 100000±500,年化 stddev ~0.33)→ 非 null,
     * sharpe 应在 |val|<5 内(年化倍数修复后合理;旧码此场景也不爆,但年化倍数仍错,
     * 仅低波动才显现爆,故本 test 守"非 null + 合理范围"而非年化倍数本身)。
     */
    @Test
    void hourlyInterval_1y_sufficientVolatility_sharpeReasonable() {
        List<TradeRecord> trades = List.of(
                trade("buy", T0, "100", "0.001", "0"),
                trade("sell", T0.plus(365, ChronoUnit.DAYS), "99.95", "0.001", "0"));

        int hours = 365 * 24;
        List<EquityPoint> curve = new ArrayList<>();
        for (int i = 0; i <= hours; i++) {
            double frac = (double) i / hours;
            double base = 100000 + (99950 - 100000) * frac;
            double wave = Math.sin(i * 0.1) * 500; // ±500 中等波动
            curve.add(new EquityPoint(T0.plus(i, ChronoUnit.HOURS), new BigDecimal(String.valueOf(base + wave))));
        }

        PerformanceMetrics m = PerformanceCalculator.calculate(trades, curve, RISK_FREE);

        assertThat(m.sharpeRatio()).as("中等波动(年化 stddev>0.1%)应非 null").isNotNull();
        assertThat(m.sharpeRatio().abs()).as("年化倍数修复后 sharpe 应在合理范围 |val|<5").isLessThan(new BigDecimal("5"));
    }

    // ---- helper ----

    private static TradeRecord trade(String side, Instant time, String price, String amount, String fee) {
        TradeRecord t = new TradeRecord();
        t.setSide(side);
        t.setTime(time);
        t.setPrice(new BigDecimal(price));
        t.setAmount(new BigDecimal(amount));
        t.setFee(fee != null ? new BigDecimal(fee) : null);
        return t;
    }
}
