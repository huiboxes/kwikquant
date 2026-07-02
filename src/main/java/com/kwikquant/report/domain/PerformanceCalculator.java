package com.kwikquant.report.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Pure-domain calculator for backtest performance metrics.
 *
 * <p>All calculations use {@link BigDecimal} for precision. The calculator is stateless
 * and side-effect free -- it only reads the input lists and returns a result record.
 *
 * <h3>Metrics produced</h3>
 * <ul>
 *   <li><b>totalReturn</b> -- from equity curve if available, otherwise from trade PnL</li>
 *   <li><b>sharpeRatio</b> -- annualized, using daily returns from equity curve</li>
 *   <li><b>maxDrawdown</b> -- peak-to-trough from equity curve</li>
 *   <li><b>winRate</b> -- fraction of profitable round-trip trades</li>
 *   <li><b>profitFactor</b> -- gross profit / gross loss (null when no losing trades)</li>
 *   <li><b>totalTrades</b> -- number of completed round-trip (buy+sell) pairs</li>
 *   <li><b>avgTradeDurationSeconds</b> -- mean hold time per round-trip</li>
 * </ul>
 */
public final class PerformanceCalculator {

    /** Default annual risk-free rate used when none is supplied. */
    private static final BigDecimal DEFAULT_RISK_FREE_RATE = new BigDecimal("0.02");

    /** Internal scale for intermediate BigDecimal arithmetic. */
    private static final int SCALE = 8;

    /** Rounding mode used throughout. */
    private static final RoundingMode RM = RoundingMode.HALF_UP;

    /** Seconds in a 365-day year. */
    private static final long SECONDS_PER_YEAR = 365L * 24 * 3600;

    /** Days per year as a double constant (for annualization). */
    private static final double DAYS_PER_YEAR = 365.0;

    private PerformanceCalculator() {
        // utility class
    }

    /**
     * Calculate performance metrics from a list of trades and an equity curve.
     *
     * @param trades       the individual trade records (buys and sells)
     * @param equityCurve  time-ordered equity snapshots
     * @param riskFreeRate annual risk-free rate; if null, defaults to 2%
     * @return a {@link PerformanceMetrics} record with all computed values
     */
    public static PerformanceMetrics calculate(
            List<TradeRecord> trades, List<EquityPoint> equityCurve, BigDecimal riskFreeRate) {

        BigDecimal rfr = riskFreeRate != null ? riskFreeRate : DEFAULT_RISK_FREE_RATE;

        // --- 1. Pair trades using FIFO ---
        List<TradePair> pairs = pairTrades(trades);

        if (pairs.isEmpty()) {
            return new PerformanceMetrics(BigDecimal.ZERO, null, null, BigDecimal.ZERO, null, 0, 0);
        }

        // --- 2. Win rate ---
        long wins = pairs.stream()
                .filter(p -> p.pnl().compareTo(BigDecimal.ZERO) > 0)
                .count();
        BigDecimal winRate = BigDecimal.valueOf(wins).divide(BigDecimal.valueOf(pairs.size()), SCALE, RM);

        // --- 3. Profit factor ---
        BigDecimal grossProfit = BigDecimal.ZERO;
        BigDecimal grossLoss = BigDecimal.ZERO;
        for (TradePair pair : pairs) {
            BigDecimal pnl = pair.pnl();
            if (pnl.compareTo(BigDecimal.ZERO) > 0) {
                grossProfit = grossProfit.add(pnl);
            } else if (pnl.compareTo(BigDecimal.ZERO) < 0) {
                grossLoss = grossLoss.add(pnl.abs());
            }
        }
        BigDecimal profitFactor =
                grossLoss.compareTo(BigDecimal.ZERO) == 0 ? null : grossProfit.divide(grossLoss, SCALE, RM);

        // --- 4. Average trade duration ---
        long totalDurationSeconds = 0;
        for (TradePair pair : pairs) {
            Duration d = Duration.between(pair.buy().getTime(), pair.sell().getTime());
            totalDurationSeconds += d.getSeconds();
        }
        long avgTradeDurationSeconds = totalDurationSeconds / pairs.size();

        // --- 5. Total return ---
        BigDecimal totalReturn;
        if (equityCurve != null && equityCurve.size() >= 2) {
            totalReturn = calculateTotalReturn(equityCurve);
        } else {
            totalReturn = calculateTotalReturnFromTrades(pairs);
        }

        // --- 6. Max drawdown ---
        BigDecimal maxDrawdown = null;
        if (equityCurve != null && equityCurve.size() >= 2) {
            maxDrawdown = calculateMaxDrawdown(equityCurve);
        }

        // --- 7. Sharpe ratio ---
        BigDecimal sharpeRatio = null;
        if (equityCurve != null && equityCurve.size() >= 2) {
            sharpeRatio = calculateSharpeRatio(equityCurve, rfr);
        }

        return new PerformanceMetrics(
                totalReturn, sharpeRatio, maxDrawdown, winRate, profitFactor, pairs.size(), avgTradeDurationSeconds);
    }

    // -----------------------------------------------------------------------
    //  FIFO trade pairing
    // -----------------------------------------------------------------------

    /**
     * Pair buy and sell trades using FIFO: the earliest unmatched buy is paired
     * with the next sell that follows it in time.
     *
     * <p>Trades are first sorted by time. A pending buy is tracked; each sell
     * closes it. If a new buy arrives while one is pending, the new buy replaces
     * the old (simulating position replacement).
     */
    private static List<TradePair> pairTrades(List<TradeRecord> trades) {
        if (trades == null || trades.isEmpty()) {
            return List.of();
        }

        List<TradeRecord> sorted = new ArrayList<>(trades);
        sorted.sort(Comparator.comparing(TradeRecord::getTime));

        List<TradePair> pairs = new ArrayList<>();
        TradeRecord pendingBuy = null;

        for (TradeRecord trade : sorted) {
            if ("buy".equalsIgnoreCase(trade.getSide())) {
                pendingBuy = trade;
            } else if ("sell".equalsIgnoreCase(trade.getSide()) && pendingBuy != null) {
                pairs.add(new TradePair(pendingBuy, trade));
                pendingBuy = null;
            }
        }
        return pairs;
    }

    // -----------------------------------------------------------------------
    //  Total return
    // -----------------------------------------------------------------------

    /**
     * Calculate total return from equity curve: (last - first) / first.
     */
    private static BigDecimal calculateTotalReturn(List<EquityPoint> equityCurve) {
        BigDecimal first = equityCurve.getFirst().equity();
        BigDecimal last = equityCurve.getLast().equity();
        if (first.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return last.subtract(first).divide(first, SCALE, RM);
    }

    /**
     * Fallback: calculate total return from trade PnL when no equity curve is
     * available. Initial capital is estimated as firstBuy.price * firstBuy.amount.
     */
    private static BigDecimal calculateTotalReturnFromTrades(List<TradePair> pairs) {
        BigDecimal totalPnl = BigDecimal.ZERO;
        for (TradePair pair : pairs) {
            totalPnl = totalPnl.add(pair.pnl());
        }
        TradeRecord firstBuy = pairs.getFirst().buy();
        BigDecimal initialCapital = firstBuy.getPrice().multiply(firstBuy.getAmount());
        if (initialCapital.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return totalPnl.divide(initialCapital, SCALE, RM);
    }

    // -----------------------------------------------------------------------
    //  Max drawdown
    // -----------------------------------------------------------------------

    /**
     * Calculate maximum drawdown from equity curve.
     *
     * <p>Tracks a running peak. Drawdown at each point is (peak - equity) / peak.
     * Returns the largest drawdown observed.
     */
    private static BigDecimal calculateMaxDrawdown(List<EquityPoint> equityCurve) {
        BigDecimal peak = equityCurve.getFirst().equity();
        BigDecimal maxDd = BigDecimal.ZERO;

        for (EquityPoint point : equityCurve) {
            BigDecimal equity = point.equity();
            if (equity.compareTo(peak) > 0) {
                peak = equity;
            }
            if (peak.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal drawdown = peak.subtract(equity).divide(peak, SCALE, RM);
                if (drawdown.compareTo(maxDd) > 0) {
                    maxDd = drawdown;
                }
            }
        }
        return maxDd;
    }

    // -----------------------------------------------------------------------
    //  Sharpe ratio
    // -----------------------------------------------------------------------

    /**
     * Calculate annualized Sharpe ratio from the equity curve.
     *
     * <ol>
     *   <li>Compute total return = (last - first) / first</li>
     *   <li>Compute total seconds from first to last point</li>
     *   <li>Annualized return = totalReturn * SECONDS_PER_YEAR / totalSeconds</li>
     *   <li>Compute daily returns between consecutive equity points</li>
     *   <li>Annualized std dev = dailyStdDev * sqrt(365)</li>
     *   <li>Sharpe = (annualizedReturn - riskFreeRate) / annualizedStdDev</li>
     * </ol>
     *
     * @return the Sharpe ratio, or {@code null} if standard deviation is zero
     */
    private static BigDecimal calculateSharpeRatio(List<EquityPoint> equityCurve, BigDecimal riskFreeRate) {
        BigDecimal first = equityCurve.getFirst().equity();
        BigDecimal last = equityCurve.getLast().equity();

        if (first.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }

        BigDecimal totalReturn = last.subtract(first).divide(first, SCALE, RM);

        long totalSeconds = Duration.between(
                        equityCurve.getFirst().time(), equityCurve.getLast().time())
                .getSeconds();
        if (totalSeconds <= 0) {
            return null;
        }

        BigDecimal annualizedReturn = totalReturn
                .multiply(BigDecimal.valueOf(SECONDS_PER_YEAR))
                .divide(BigDecimal.valueOf(totalSeconds), SCALE, RM);

        // Daily returns
        List<BigDecimal> dailyReturns = new ArrayList<>();
        for (int i = 1; i < equityCurve.size(); i++) {
            BigDecimal prev = equityCurve.get(i - 1).equity();
            BigDecimal curr = equityCurve.get(i).equity();
            if (prev.compareTo(BigDecimal.ZERO) != 0) {
                dailyReturns.add(curr.subtract(prev).divide(prev, SCALE, RM));
            }
        }

        if (dailyReturns.isEmpty()) {
            return null;
        }

        BigDecimal dailyStdDev = standardDeviation(dailyReturns);
        if (dailyStdDev.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }

        BigDecimal sqrtDays = BigDecimal.valueOf(Math.sqrt(DAYS_PER_YEAR));
        BigDecimal annualizedStdDev = dailyStdDev.multiply(sqrtDays);

        return annualizedReturn.subtract(riskFreeRate).divide(annualizedStdDev, SCALE, RM);
    }

    // -----------------------------------------------------------------------
    //  Standard deviation (sample)
    // -----------------------------------------------------------------------

    /**
     * Compute sample standard deviation (n-1 divisor) of a list of BigDecimal values.
     *
     * <p>Uses {@link BigDecimal#sqrt(MathContext)} for the final square root.
     *
     * @param values non-empty list of values
     * @return the sample standard deviation
     */
    private static BigDecimal standardDeviation(List<BigDecimal> values) {
        if (values.size() < 2) {
            return BigDecimal.ZERO;
        }

        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal v : values) {
            sum = sum.add(v);
        }
        BigDecimal mean = sum.divide(BigDecimal.valueOf(values.size()), SCALE, RM);

        BigDecimal sumSquaredDiffs = BigDecimal.ZERO;
        for (BigDecimal v : values) {
            BigDecimal diff = v.subtract(mean);
            sumSquaredDiffs = sumSquaredDiffs.add(diff.multiply(diff));
        }

        BigDecimal variance = sumSquaredDiffs.divide(BigDecimal.valueOf(values.size() - 1L), SCALE, RM);

        return variance.sqrt(new MathContext(SCALE, RM));
    }

    // -----------------------------------------------------------------------
    //  Internal record for a matched buy-sell pair
    // -----------------------------------------------------------------------

    /**
     * A matched round-trip trade: one buy followed by one sell.
     *
     * @param buy  the opening buy trade
     * @param sell the closing sell trade
     */
    private record TradePair(TradeRecord buy, TradeRecord sell) {

        /**
         * Calculate PnL for this round-trip.
         *
         * <pre>
         * pnl = (sell.price * sell.amount) - (buy.price * buy.amount) - buyFee - sellFee
         * </pre>
         *
         * <p>Null fees are treated as zero.
         */
        BigDecimal pnl() {
            BigDecimal buyValue = buy.getPrice().multiply(buy.getAmount());
            BigDecimal sellValue = sell.getPrice().multiply(sell.getAmount());
            BigDecimal buyFee = buy.getFee() != null ? buy.getFee() : BigDecimal.ZERO;
            BigDecimal sellFee = sell.getFee() != null ? sell.getFee() : BigDecimal.ZERO;
            return sellValue.subtract(buyValue).subtract(buyFee).subtract(sellFee);
        }
    }
}
