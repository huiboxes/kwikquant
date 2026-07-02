package com.kwikquant.report.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
