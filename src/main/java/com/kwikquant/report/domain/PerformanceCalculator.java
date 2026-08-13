package com.kwikquant.report.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
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

    /** {@code TradeRecord#getSide()} 的买入侧标识（大小写不敏感，见 {@link #pairTrades}）。 */
    public static final String SIDE_BUY = "buy";

    /** {@code TradeRecord#getSide()} 的卖出侧标识（大小写不敏感，见 {@link #pairTrades}）。 */
    public static final String SIDE_SELL = "sell";

    /** Default annual risk-free rate used when none is supplied. */
    private static final BigDecimal DEFAULT_RISK_FREE_RATE = new BigDecimal("0.02");

    /** Internal scale for ratio/return BigDecimal arithmetic (decimal places, O(0.01) 量级足够)。 */
    private static final int SCALE = 8;

    /** Rounding mode used throughout. */
    private static final RoundingMode RM = RoundingMode.HALF_UP;

    /** 统计中间量(均值/方差/stddev)用有效数字而非小数位:回测方差常 ~1e-10,
     * 若用 {@code SCALE} 小数位 divide 会舍入到 0 → stddev=0 → sharpe 误判 null(见 report id=2 regression)。 */
    private static final MathContext STAT_MC = new MathContext(20, RM);

    /** Seconds in a 365-day year. */
    private static final long SECONDS_PER_YEAR = 365L * 24 * 3600;

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

    /**
     * Enrich trade records with per-trade realizedPnl and cumulative equity. Uses FIFO pairing:
     * each sell's pnl is computed from its matched buy. Buys get realizedPnl = -fee (cost only).
     * Equity tracks cumulative PnL starting from estimated initial capital.
     *
     * <p>Mutates the input TradeRecord objects in place. Must be called before persistence
     * (trade IDs may not yet be assigned).
     */
    public static void enrichTrades(List<TradeRecord> trades) {
        if (trades == null || trades.isEmpty()) {
            return;
        }

        List<TradeRecord> sorted = new ArrayList<>(trades);
        sorted.sort(Comparator.comparing(TradeRecord::getTime));

        List<TradePair> pairs = pairTrades(trades);

        // Build an identity map: sell trade object reference → total pnl（一笔 sell 可能跨多个 buy lot
        // 部分匹配，故对同一 sell 累加而不是覆盖）。
        java.util.IdentityHashMap<TradeRecord, BigDecimal> sellPnlMap = new java.util.IdentityHashMap<>();
        for (TradePair pair : pairs) {
            sellPnlMap.merge(pair.sell(), pair.pnl(), BigDecimal::add);
        }

        BigDecimal initialCapital = BigDecimal.ZERO;
        for (TradeRecord t : sorted) {
            if (SIDE_BUY.equalsIgnoreCase(t.getSide())) {
                initialCapital = t.getPrice().multiply(t.getAmount());
                break;
            }
        }

        BigDecimal cumulativeEquity = initialCapital;
        for (TradeRecord t : sorted) {
            BigDecimal fee = t.getFee() != null ? t.getFee() : BigDecimal.ZERO;
            if (SIDE_SELL.equalsIgnoreCase(t.getSide()) && sellPnlMap.containsKey(t)) {
                BigDecimal closeDelta = sellPnlMap.get(t).add(matchedBuyFee(pairs, t));
                t.setRealizedPnl(closeDelta);
                cumulativeEquity = cumulativeEquity.add(closeDelta);
            } else {
                t.setRealizedPnl(fee.negate());
                cumulativeEquity = cumulativeEquity.subtract(fee);
            }
            t.setEquity(cumulativeEquity);
        }
    }

    /** 买入费用已在开仓 fill 计入权益；平仓 delta 加回 pair.pnl 中的买费，避免再次扣除。 */
    private static BigDecimal matchedBuyFee(List<TradePair> pairs, TradeRecord sell) {
        return pairs.stream()
                .filter(pair -> pair.sell() == sell)
                .map(TradePair::buyFeeShare)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // -----------------------------------------------------------------------

    /**
     * Pair buy and sell trades using quantity-based FIFO: maintains a queue of open buy lots
     * (each with its own remaining quantity); each sell is matched against the front of the
     * queue, consuming quantity from one or more lots until the sell is fully matched or the
     * queue is exhausted.
     *
     * <p>This correctly handles multiple partial fills on either side (e.g. one buy followed by
     * two partial sells, or two buys merged into one sell) — every matched quantity segment
     * becomes its own {@link TradePair} so its notional value is never silently dropped.
     * A sell quantity that exceeds all open buy lots (data anomaly / naked short) has its
     * unmatched remainder produce no pair, consistent with prior behavior for un-pairable trades.
     */
    private static List<TradePair> pairTrades(List<TradeRecord> trades) {
        if (trades == null || trades.isEmpty()) {
            return List.of();
        }

        List<TradeRecord> sorted = new ArrayList<>(trades);
        sorted.sort(Comparator.comparing(TradeRecord::getTime));

        Deque<OpenLot> openBuys = new ArrayDeque<>();
        List<TradePair> pairs = new ArrayList<>();

        for (TradeRecord trade : sorted) {
            if (SIDE_BUY.equalsIgnoreCase(trade.getSide())) {
                openBuys.addLast(new OpenLot(trade));
            } else if (SIDE_SELL.equalsIgnoreCase(trade.getSide())) {
                BigDecimal remaining = trade.getAmount();
                BigDecimal sellFee = trade.getFee() != null ? trade.getFee() : BigDecimal.ZERO;
                while (remaining.signum() > 0 && !openBuys.isEmpty()) {
                    OpenLot lot = openBuys.peekFirst();
                    BigDecimal matchQty = remaining.min(lot.remainingQty);
                    BigDecimal buyFeeShare = feeShare(lot.buy.getFee(), matchQty, lot.buy.getAmount());
                    BigDecimal sellFeeShare = feeShare(sellFee, matchQty, trade.getAmount());
                    pairs.add(new TradePair(lot.buy, trade, matchQty, buyFeeShare, sellFeeShare));
                    lot.remainingQty = lot.remainingQty.subtract(matchQty);
                    remaining = remaining.subtract(matchQty);
                    if (lot.remainingQty.signum() == 0) {
                        openBuys.pollFirst();
                    }
                }
            }
        }
        return pairs;
    }

    private static BigDecimal feeShare(BigDecimal totalFee, BigDecimal matchQty, BigDecimal totalQty) {
        if (totalFee == null || totalQty == null || totalQty.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return totalFee.multiply(matchQty).divide(totalQty, SCALE, RM);
    }

    /** An open (not yet fully sold) buy lot tracked during FIFO matching. */
    private static final class OpenLot {
        final TradeRecord buy;
        BigDecimal remainingQty;

        OpenLot(TradeRecord buy) {
            this.buy = buy;
            this.remainingQty = buy.getAmount();
        }
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

        // Daily returns(用 STAT_MC 有效数字,避免小 return ~1e-6 被小数位截断)
        List<BigDecimal> dailyReturns = new ArrayList<>();
        for (int i = 1; i < equityCurve.size(); i++) {
            BigDecimal prev = equityCurve.get(i - 1).equity();
            BigDecimal curr = equityCurve.get(i).equity();
            if (prev.compareTo(BigDecimal.ZERO) != 0) {
                dailyReturns.add(curr.subtract(prev).divide(prev, STAT_MC));
            }
        }

        if (dailyReturns.isEmpty()) {
            return null;
        }

        BigDecimal dailyStdDev = standardDeviation(dailyReturns);
        if (dailyStdDev.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }

        // 年化倍数:按 equity point 实际平均间隔算 pointsPerYear(1h interval→8760,1d→365),
        // 而非硬编码 sqrt(365)。原 bug:把相邻点 return 当 daily return,1h interval 时年化
        // 倍数该 sqrt(8760) 差 sqrt(24)≈4.9 倍,低波动场景严重低估年化 stddev → sharpe 爆到
        // 几百(用户实测 -939)。dailyReturns 实际是"相邻点 return"(period return,非 daily),
        // 故年化倍数必须按 pointsPerYear(每 年点数)而非固定 sqrt(365)。
        BigDecimal avgIntervalSeconds =
                BigDecimal.valueOf(totalSeconds).divide(BigDecimal.valueOf(equityCurve.size() - 1L), SCALE, RM);
        BigDecimal pointsPerYear = BigDecimal.valueOf(SECONDS_PER_YEAR).divide(avgIntervalSeconds, SCALE, RM);
        BigDecimal annualizationFactor = BigDecimal.valueOf(Math.sqrt(pointsPerYear.doubleValue()));
        BigDecimal annualizedStdDev = dailyStdDev.multiply(annualizationFactor);

        // 低波动约束:年化 stddev < 0.1%(1e-3)时 sharpe 无意义 —— 数据接近无波动(策略几乎
        // 不交易或 equity 几乎平线),公式放大器会把微小负偏 + 极小 stddev 爆成几百。
        // 诚实返 null,前端显"—"避免误导(用户实测 -939 即此场景:总收益 -0.05% 但 sharpe 爆)。
        // 0.1% 阈值远低于真实市场年化波动(加密 50-80%、股票 15-25%),只过滤"数据不足"场景。
        if (annualizedStdDev.compareTo(new BigDecimal("0.001")) < 0) {
            return null;
        }

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
        BigDecimal mean = sum.divide(BigDecimal.valueOf(values.size()), STAT_MC);

        BigDecimal sumSquaredDiffs = BigDecimal.ZERO;
        for (BigDecimal v : values) {
            BigDecimal diff = v.subtract(mean);
            sumSquaredDiffs = sumSquaredDiffs.add(diff.multiply(diff));
        }

        // 关键:方差 ~1e-10 量级,必须用 STAT_MC(有效数字)而非 SCALE(小数位),
        // 否则 divide 舍入到 0 → sqrt=0 → sharpe 误判 null(report id=2 regression)。
        BigDecimal variance = sumSquaredDiffs.divide(BigDecimal.valueOf(values.size() - 1L), STAT_MC);

        return variance.sqrt(STAT_MC);
    }

    // -----------------------------------------------------------------------
    //  Internal record for a matched buy-sell pair
    // -----------------------------------------------------------------------

    /**
     * A matched round-trip quantity segment: {@code qty} units bought via {@code buy} and sold
     * via {@code sell} (a single buy/sell trade may be split across multiple {@code TradePair}s
     * when matched via FIFO against multiple counterparties).
     *
     * @param buy           the opening buy trade
     * @param sell          the closing sell trade
     * @param qty           the matched quantity (may be less than either trade's full amount)
     * @param buyFeeShare   the portion of the buy trade's fee attributed to this matched quantity
     * @param sellFeeShare  the portion of the sell trade's fee attributed to this matched quantity
     */
    private record TradePair(
            TradeRecord buy, TradeRecord sell, BigDecimal qty, BigDecimal buyFeeShare, BigDecimal sellFeeShare) {

        /**
         * Calculate PnL for this matched quantity segment.
         *
         * <pre>
         * pnl = (sell.price * qty) - (buy.price * qty) - buyFeeShare - sellFeeShare
         * </pre>
         */
        BigDecimal pnl() {
            BigDecimal buyValue = buy.getPrice().multiply(qty);
            BigDecimal sellValue = sell.getPrice().multiply(qty);
            return sellValue.subtract(buyValue).subtract(buyFeeShare).subtract(sellFeeShare);
        }
    }
}
