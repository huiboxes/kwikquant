package com.kwikquant.report.domain;

import com.kwikquant.shared.types.PositionEffect;
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
 * <h3>Pairing models (docs/perp-backtest-spec.md §8.2)</h3>
 * <ul>
 *   <li><b>SPOT</b> -- quantity-based FIFO over buy/sell sides; a sell consumes open buy
 *       lots front-to-back. Unchanged legacy behavior.</li>
 *   <li><b>PERP</b> -- net-position signed FIFO over {@code positionEffect}: OPEN_* lots
 *       queue in the position direction, CLOSE_* (and the closing segment of a
 *       cross-through OPEN_*) consumes opposite-direction lots. {@code side} is a derived
 *       quantity on PERP rows (buy != opening) and must not drive pairing.</li>
 * </ul>
 *
 * <h3>Metrics produced</h3>
 * <ul>
 *   <li><b>totalReturn</b> -- from equity curve if available, otherwise from trade PnL</li>
 *   <li><b>sharpeRatio</b> -- annualized, using daily returns from equity curve</li>
 *   <li><b>maxDrawdown</b> -- peak-to-trough from equity curve</li>
 *   <li><b>winRate</b> -- fraction of profitable round-trip trades (PERP: gross pairing
 *       PnL -- funding and unrealized PnL stay in the equity curve, not in pairs)</li>
 *   <li><b>profitFactor</b> -- gross profit / gross loss (null when no losing trades)</li>
 *   <li><b>totalTrades</b> -- number of completed round-trip (open+close) pairs</li>
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
     * Calculate performance metrics from a list of trades and an equity curve (SPOT pairing).
     *
     * @param trades       the individual trade records (buys and sells)
     * @param equityCurve  time-ordered equity snapshots
     * @param riskFreeRate annual risk-free rate; if null, defaults to 2%
     * @return a {@link PerformanceMetrics} record with all computed values
     */
    public static PerformanceMetrics calculate(
            List<TradeRecord> trades, List<EquityPoint> equityCurve, BigDecimal riskFreeRate) {
        return calculate(trades, equityCurve, riskFreeRate, false);
    }

    /**
     * Calculate performance metrics, dispatching the pairing model by report market type.
     *
     * @param perp true = PERP report: trades carry {@code positionEffect} and pair via
     *             net-position signed FIFO; false = legacy SPOT buy/sell FIFO
     */
    public static PerformanceMetrics calculate(
            List<TradeRecord> trades, List<EquityPoint> equityCurve, BigDecimal riskFreeRate, boolean perp) {

        BigDecimal rfr = riskFreeRate != null ? riskFreeRate : DEFAULT_RISK_FREE_RATE;

        // --- 1. Pair trades using FIFO ---
        List<TradePair> pairs = perp ? pairPerpTrades(trades) : pairTrades(trades);

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
            Duration d = Duration.between(pair.open().getTime(), pair.close().getTime());
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
     *
     * <p>Note: initial capital is <b>estimated</b> as firstBuy.price * firstBuy.amount, which is
     * only correct when the first buy is the full position. For accurate equity tracking pass
     * the real initial capital via {@link #enrichTrades(List, BigDecimal)}.
     */
    public static void enrichTrades(List<TradeRecord> trades) {
        enrichTrades(trades, null);
    }

    /**
     * Enrich trade records with per-trade realizedPnl and cumulative equity, starting cumulative
     * equity from the given {@code initialCapital}.
     *
     * <p>When {@code initialCapital} is null (e.g. no equity curve available), falls back to the
     * legacy estimate of {@code firstBuy.price * firstBuy.amount} -- this is only an approximation
     * and is kept solely for backward compatibility with the no-capital path; callers that have an
     * equity curve should always pass {@code equityCurve.getFirst().equity()} so per-trade equity
     * aligns with the equity curve (P1-2: previously trades[].equity started from ~first buy
     * notional instead of the real 100,000, contradicting the equity curve).
     *
     * <p>Mutates the input TradeRecord objects in place.
     *
     * @param trades         the trade records to enrich (mutated in place)
     * @param initialCapital real initial capital (e.g. first equity point); null falls back to
     *                       firstBuy.price * firstBuy.amount estimate
     */
    public static void enrichTrades(List<TradeRecord> trades, BigDecimal initialCapital) {
        enrichTrades(trades, initialCapital, false);
    }

    /**
     * PERP 分派版({@code perp=true} 按 {@code positionEffect} 净持仓配对)。PERP 报告的逐笔
     * {@code equity} 一律置 null——trade 口径累计权益不含未实现盈亏与资金费,与权益曲线必然
     * 背离,置空避免误读(docs/perp-backtest-spec.md §8.2,组合报告同先例)。
     */
    public static void enrichTrades(List<TradeRecord> trades, BigDecimal initialCapital, boolean perp) {
        if (trades == null || trades.isEmpty()) {
            return;
        }

        boolean multiSymbol = trades.stream().anyMatch(t -> t.getSymbol() != null);
        // 按标的分组做 FIFO 配对与逐笔累计:单标的报告只有一组,结果与逐笔全局处理完全一致;
        // 组合报告各标的独立配对(跨标的的 buy/sell 不构成往返,不能互相配对)。
        for (List<TradeRecord> group : groupBySymbol(trades).values()) {
            enrichTradesWithinSymbol(group, initialCapital, perp);
        }
        if (multiSymbol || perp) {
            // 组合报告的逐笔"累计权益"无单一标的口径(全组合权益见权益曲线);PERP 见方法 javadoc
            for (TradeRecord t : trades) {
                t.setEquity(null);
            }
        }
    }

    private static void enrichTradesWithinSymbol(List<TradeRecord> trades, BigDecimal initialCapital, boolean perp) {
        List<TradeRecord> sorted = new ArrayList<>(trades);
        sorted.sort(Comparator.comparing(TradeRecord::getTime));

        List<TradePair> pairs;
        java.util.Map<TradeRecord, BigDecimal> lotFeeByTrade = java.util.Map.of();
        if (perp) {
            PerpPairing pairing = pairPerpTradesWithinSymbol(trades);
            pairs = pairing.pairs();
            lotFeeByTrade = pairing.lotFeeByTrade();
        } else {
            pairs = pairTradesWithinSymbol(trades);
        }

        // Build an identity map: close trade object reference → total pnl（一笔平仓可能跨多个开仓 lot
        // 部分匹配，故对同一 close 累加而不是覆盖）。
        java.util.IdentityHashMap<TradeRecord, BigDecimal> closePnlMap = new java.util.IdentityHashMap<>();
        for (TradePair pair : pairs) {
            closePnlMap.merge(pair.close(), pair.pnl(), BigDecimal::add);
        }

        // 真实初始资金优先；为空时降级为首笔开仓名义额估算（仅向后兼容无 equityCurve 的降级路径）。
        BigDecimal startingCapital = initialCapital;
        if (startingCapital == null) {
            startingCapital = BigDecimal.ZERO;
            for (TradeRecord t : sorted) {
                if (isOpenLeg(t, perp)) {
                    startingCapital = t.getPrice().multiply(t.getAmount());
                    break;
                }
            }
        }

        BigDecimal cumulativeEquity = startingCapital;
        for (TradeRecord t : sorted) {
            BigDecimal fee = t.getFee() != null ? t.getFee() : BigDecimal.ZERO;
            // SPOT 平仓腿 = sell 且在配对 map 中;PERP 平仓腿 = 在配对 map 中(side 是派生量,
            // CLOSE_SHORT 的 side=buy,不能按 side 判)
            boolean closeLeg = perp
                    ? closePnlMap.containsKey(t)
                    : SIDE_SELL.equalsIgnoreCase(t.getSide()) && closePnlMap.containsKey(t);
            if (closeLeg) {
                // 加回配对段中的开仓费份额(已由开仓行 -fee 承担);PERP 穿零反转行同时是开仓腿,
                // 其新 lot 归属费用没有别的行承担,必须在此扣除,否则 Σ realizedPnl 不守恒(§8.2)
                BigDecimal closeDelta = closePnlMap
                        .get(t)
                        .add(matchedOpenFee(pairs, t))
                        .subtract(lotFeeByTrade.getOrDefault(t, BigDecimal.ZERO));
                t.setRealizedPnl(closeDelta);
                cumulativeEquity = cumulativeEquity.add(closeDelta);
            } else {
                t.setRealizedPnl(fee.negate());
                cumulativeEquity = cumulativeEquity.subtract(fee);
            }
            t.setEquity(cumulativeEquity);
        }
    }

    /** 开仓腿判定:SPOT = buy 行;PERP = OPEN_* 意图行。 */
    private static boolean isOpenLeg(TradeRecord t, boolean perp) {
        if (!perp) {
            return SIDE_BUY.equalsIgnoreCase(t.getSide());
        }
        PositionEffect effect = parseEffect(t.getPositionEffect());
        return effect == PositionEffect.OPEN_LONG || effect == PositionEffect.OPEN_SHORT;
    }

    /** 按成交标的分组(单标的报告 symbol 为 null,归同一组);LinkedHashMap 保持输入顺序。 */
    private static java.util.Map<String, List<TradeRecord>> groupBySymbol(List<TradeRecord> trades) {
        java.util.Map<String, List<TradeRecord>> groups = new java.util.LinkedHashMap<>();
        for (TradeRecord t : trades) {
            String key = t.getSymbol() == null ? "" : t.getSymbol();
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(t);
        }
        return groups;
    }

    /** 开仓费用已在开仓 fill 计入权益；平仓 delta 加回 pair.pnl 中的开仓费份额，避免再次扣除。 */
    private static BigDecimal matchedOpenFee(List<TradePair> pairs, TradeRecord close) {
        return pairs.stream()
                .filter(pair -> pair.close() == close)
                .map(TradePair::openFeeShare)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // -----------------------------------------------------------------------

    /**
     * Pair buy and sell trades using quantity-based FIFO, independently per symbol.
     *
     * <p>单标的报告(全部成交 symbol 相同或为 null)只产生一个分组,结果与逐笔全局配对完全一致;
     * 组合(多标的)报告各标的独立配对——跨标的的 buy/sell 不构成往返,不能互相配对。
     */
    private static List<TradePair> pairTrades(List<TradeRecord> trades) {
        if (trades == null || trades.isEmpty()) {
            return List.of();
        }
        List<TradePair> pairs = new ArrayList<>();
        for (List<TradeRecord> group : groupBySymbol(trades).values()) {
            pairs.addAll(pairTradesWithinSymbol(group));
        }
        return pairs;
    }

    /**
     * Pair buy and sell trades of a single symbol using quantity-based FIFO: maintains a queue of
     * open buy lots (each with its own remaining quantity); each sell is matched against the front
     * of the queue, consuming quantity from one or more lots until the sell is fully matched or the
     * queue is exhausted.
     *
     * <p>This correctly handles multiple partial fills on either side (e.g. one buy followed by
     * two partial sells, or two buys merged into one sell) — every matched quantity segment
     * becomes its own {@link TradePair} so its notional value is never silently dropped.
     * A sell quantity that exceeds all open buy lots (data anomaly / naked short) has its
     * unmatched remainder produce no pair, consistent with prior behavior for un-pairable trades.
     */
    private static List<TradePair> pairTradesWithinSymbol(List<TradeRecord> trades) {
        List<TradeRecord> sorted = new ArrayList<>(trades);
        sorted.sort(Comparator.comparing(TradeRecord::getTime));

        Deque<OpenLot> openBuys = new ArrayDeque<>();
        List<TradePair> pairs = new ArrayList<>();

        for (TradeRecord trade : sorted) {
            if (SIDE_BUY.equalsIgnoreCase(trade.getSide())) {
                openBuys.addLast(OpenLot.ofSpot(trade));
            } else if (SIDE_SELL.equalsIgnoreCase(trade.getSide())) {
                BigDecimal remaining = trade.getAmount();
                BigDecimal sellFee = trade.getFee() != null ? trade.getFee() : BigDecimal.ZERO;
                while (remaining.signum() > 0 && !openBuys.isEmpty()) {
                    OpenLot lot = openBuys.peekFirst();
                    BigDecimal matchQty = remaining.min(lot.remainingQty);
                    BigDecimal buyFeeShare = lot.openFeeShare(matchQty);
                    BigDecimal sellFeeShare = feeShare(sellFee, matchQty, trade.getAmount());
                    pairs.add(new TradePair(lot.trade, trade, matchQty, buyFeeShare, sellFeeShare, true));
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

    // -----------------------------------------------------------------------
    //  PERP pairing (net-position signed FIFO, docs/perp-backtest-spec.md §8.2)
    // -----------------------------------------------------------------------

    /** PERP 版 {@link #pairTrades}:按标的分组后走净持仓 signed FIFO 配对。 */
    private static List<TradePair> pairPerpTrades(List<TradeRecord> trades) {
        if (trades == null || trades.isEmpty()) {
            return List.of();
        }
        List<TradePair> pairs = new ArrayList<>();
        for (List<TradeRecord> group : groupBySymbol(trades).values()) {
            pairs.addAll(pairPerpTradesWithinSymbol(group).pairs());
        }
        return pairs;
    }

    /**
     * PERP 配对结果:pairs + 每个建 lot 行的归属费用(对象同一性为键)。lotFeeByTrade 供
     * enrichTrades 扣除穿零反转行的开仓腿费用(SPOT 开仓行 fee 由 realizedPnl=-fee 承担,
     * PERP 反转行的 fee 拆两段,lot 段只能在 closeDelta 里扣,见 §8.2 守恒说明)。
     */
    private record PerpPairing(List<TradePair> pairs, java.util.Map<TradeRecord, BigDecimal> lotFeeByTrade) {}

    /**
     * PERP 单标的配对:净持仓 signed FIFO,与账本应用规则(perp-backtest-spec §3.3)同构。
     *
     * <p>净持仓不变式保证 lots 队列方向单一:异号 delta(OPEN_* 穿零反转)先把对侧 lot 全部
     * 消耗成 CLOSE 配对段,余量再转新方向 lot——引擎侧被拆成两段的反转成交在 trade 行上是
     * 用户视角一条,配对在此还原两段语义。CLOSE_* 行只会消耗 lot(超仓已被引擎闸门拒,不会
     * 出现在数据中;防御性出现则同 SPOT naked 语义宽容跳过,不成对)。
     *
     * <p>同一时间戳的多笔成交按输入顺序处理(TimSort 稳定)——引擎输出顺序即真实发生顺序
     * (强平行先于本 bar 撮合成交,§4.1)。
     */
    private static PerpPairing pairPerpTradesWithinSymbol(List<TradeRecord> trades) {
        List<TradeRecord> sorted = new ArrayList<>(trades);
        sorted.sort(Comparator.comparing(TradeRecord::getTime));

        Deque<OpenLot> lots = new ArrayDeque<>();
        boolean lotsAreLong = false; // 当前 lot 队列方向(flat 时无意义,加 lot 时重置)
        List<TradePair> pairs = new ArrayList<>();
        java.util.IdentityHashMap<TradeRecord, BigDecimal> lotFeeByTrade = new java.util.IdentityHashMap<>();

        for (TradeRecord trade : sorted) {
            PositionEffect effect = parseEffect(trade.getPositionEffect());
            BigDecimal qty = trade.getAmount();
            BigDecimal fee = trade.getFee() != null ? trade.getFee() : BigDecimal.ZERO;
            boolean opening = effect == PositionEffect.OPEN_LONG || effect == PositionEffect.OPEN_SHORT;
            // signed delta 方向(§3.2):OPEN_LONG/CLOSE_SHORT 为正(多头侧),OPEN_SHORT/CLOSE_LONG 为负
            boolean longDelta = effect == PositionEffect.OPEN_LONG || effect == PositionEffect.CLOSE_SHORT;

            BigDecimal remaining = qty;
            if (!lots.isEmpty() && lotsAreLong != longDelta) {
                // 异号:FIFO 消耗对侧 lot 生成平仓配对段(CLOSE_* 正常平仓 / OPEN_* 穿零反转第一段)
                while (remaining.signum() > 0 && !lots.isEmpty()) {
                    OpenLot lot = lots.peekFirst();
                    BigDecimal matchQty = remaining.min(lot.remainingQty);
                    BigDecimal openFeeShare = lot.openFeeShare(matchQty);
                    BigDecimal closeFeeShare = feeShare(fee, matchQty, qty);
                    pairs.add(new TradePair(lot.trade, trade, matchQty, openFeeShare, closeFeeShare, lotsAreLong));
                    lot.remainingQty = lot.remainingQty.subtract(matchQty);
                    remaining = remaining.subtract(matchQty);
                    if (lot.remainingQty.signum() == 0) {
                        lots.pollFirst();
                    }
                }
            }
            if (remaining.signum() > 0 && opening) {
                // 开仓(含穿零反转余量):加新方向 lot,fee 按余量占比归属(非反转时即全额)
                BigDecimal lotFee = remaining.compareTo(qty) == 0 ? fee : feeShare(fee, remaining, qty);
                if (lots.isEmpty()) {
                    lotsAreLong = longDelta;
                }
                lots.addLast(new OpenLot(trade, remaining, remaining, lotFee));
                lotFeeByTrade.put(trade, lotFee);
            }
            // CLOSE_* 消耗后仍有余量(超仓平仓):引擎闸门已拒不会出现,防御性宽容跳过不成对
        }
        return new PerpPairing(pairs, lotFeeByTrade);
    }

    /** 解析 PERP 行四向意图;null/非法值 = 数据损坏(提交入口已校验,此处 fail-closed 防御)。 */
    private static PositionEffect parseEffect(String positionEffect) {
        if (positionEffect == null) {
            throw new IllegalArgumentException("PERP trade positionEffect must not be null");
        }
        try {
            return PositionEffect.valueOf(positionEffect);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid positionEffect: " + positionEffect, e);
        }
    }

    private static BigDecimal feeShare(BigDecimal totalFee, BigDecimal matchQty, BigDecimal totalQty) {
        if (totalFee == null || totalQty == null || totalQty.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return totalFee.multiply(matchQty).divide(totalQty, SCALE, RM);
    }

    /**
     * An open (not yet fully closed) lot tracked during FIFO matching.
     *
     * <p>{@code lotQty}/{@code lotFee} 是 lot 建立时的数量与归属费用(分摊基数):SPOT 行即
     * trade 全量;PERP 穿零反转行只有余量部分成为 lot,fee 按余量占比归属。
     */
    private static final class OpenLot {
        final TradeRecord trade;
        final BigDecimal lotQty;
        final BigDecimal lotFee;
        BigDecimal remainingQty;

        OpenLot(TradeRecord trade, BigDecimal lotQty, BigDecimal remainingQty, BigDecimal lotFee) {
            this.trade = trade;
            this.lotQty = lotQty;
            this.remainingQty = remainingQty;
            this.lotFee = lotFee;
        }

        static OpenLot ofSpot(TradeRecord buy) {
            return new OpenLot(buy, buy.getAmount(), buy.getAmount(), buy.getFee());
        }

        /** 本 lot 归属费用中按 matchQty 分摊的份额(与既有 SPOT 公式逐位一致)。 */
        BigDecimal openFeeShare(BigDecimal matchQty) {
            return feeShare(lotFee, matchQty, lotQty);
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
     * Fallback: calculate total return from trade PnL when no equity curve is available.
     *
     * <p>初始资本 = 每个标的首笔配对开仓的名义本金之和。单标的退化为"首笔配对开仓名义本金"
     * (与既有口径逐位一致);组合(多标的)按标的分别取首笔再求和,避免用单一标的的本金做
     * 跨标的总盈亏的分母、系统性放大收益率。
     */
    private static BigDecimal calculateTotalReturnFromTrades(List<TradePair> pairs) {
        BigDecimal totalPnl = BigDecimal.ZERO;
        for (TradePair pair : pairs) {
            totalPnl = totalPnl.add(pair.pnl());
        }
        java.util.Map<String, TradeRecord> firstOpenBySymbol = new java.util.LinkedHashMap<>();
        for (TradePair pair : pairs) {
            TradeRecord open = pair.open();
            String key = open.getSymbol() == null ? "" : open.getSymbol();
            firstOpenBySymbol.putIfAbsent(key, open);
        }
        BigDecimal initialCapital = BigDecimal.ZERO;
        for (TradeRecord firstOpen : firstOpenBySymbol.values()) {
            initialCapital = initialCapital.add(firstOpen.getPrice().multiply(firstOpen.getAmount()));
        }
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
     * A matched round-trip quantity segment: {@code qty} units opened via {@code open} and closed
     * via {@code close} (a single trade may be split across multiple {@code TradePair}s when
     * matched via FIFO against multiple counterparties).
     *
     * @param open          the opening trade (SPOT buy; PERP OPEN_* or the opening leg of a reversal)
     * @param close         the closing trade (SPOT sell; PERP CLOSE_* or the closing leg of a reversal)
     * @param qty           the matched quantity (may be less than either trade's full amount)
     * @param openFeeShare  the portion of the open trade's fee attributed to this matched quantity
     * @param closeFeeShare the portion of the close trade's fee attributed to this matched quantity
     * @param longSide      true = long round-trip (SPOT 恒 true; PERP 按被消耗 lot 的方向)
     */
    private record TradePair(
            TradeRecord open,
            TradeRecord close,
            BigDecimal qty,
            BigDecimal openFeeShare,
            BigDecimal closeFeeShare,
            boolean longSide) {

        /**
         * Calculate PnL for this matched quantity segment (毛口径,与内核 closed_pnl 同式;
         * 资金费不归入配对段,perp-backtest-spec §8.2)。
         *
         * <pre>
         * long:  pnl = (close.price - open.price) * qty - openFeeShare - closeFeeShare
         * short: pnl = (open.price - close.price) * qty - openFeeShare - closeFeeShare
         * </pre>
         */
        BigDecimal pnl() {
            BigDecimal openValue = open.getPrice().multiply(qty);
            BigDecimal closeValue = close.getPrice().multiply(qty);
            BigDecimal gross = longSide ? closeValue.subtract(openValue) : openValue.subtract(closeValue);
            return gross.subtract(openFeeShare).subtract(closeFeeShare);
        }
    }
}
