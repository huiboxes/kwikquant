package com.kwikquant.trading.domain;

import com.kwikquant.shared.types.OrderSide;
import com.kwikquant.shared.types.OrderType;
import com.kwikquant.shared.types.PriceLevel;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 撮合内核纯函数。Backtest 和 Paper 物理共享，确保撮合语义一致；Live 不调（CCXT 交易所撮合）。
 *
 * <p>无状态、无 IO、无副作用。相同输入永远产生相同输出（除 externalFillId UUID 和 filledAt timestamp）。
 *
 * <p>v1 不模拟部分成交（{@code MatchConfig.partialFillEnabled=false}），满足条件即全成。
 *
 * <p>Stop/TakeProfit 类型本 Wave 不展开：状态机和持久化已支持，但 Kernel 不主动触发（需 strategy 监听价格自行 fire）。
 */
public final class MatchingKernel {

    private static final BigDecimal BPS_DIVISOR = BigDecimal.valueOf(10_000);

    private MatchingKernel() {}

    public static Optional<Fill> match(Order order, MarketSnapshot snap, MatchConfig cfg) {
        if (order.getStatus() != null && order.getStatus().isTerminal()) {
            return Optional.empty();
        }
        if (order.remainingQty().signum() <= 0) {
            return Optional.empty();
        }
        return switch (order.getOrderType()) {
            case MARKET -> matchMarket(order, snap, cfg);
            case LIMIT -> matchLimit(order, snap, cfg);
            // Stop / TakeProfit / Trailing 本 Wave 不主动撮合（由 strategy 自行 fire）
            case STOP_MARKET, STOP_LIMIT, TAKE_PROFIT_MARKET, TAKE_PROFIT_LIMIT, TRAILING_STOP -> Optional.empty();
        };
    }

    private static Optional<Fill> matchMarket(Order order, MarketSnapshot snap, MatchConfig cfg) {
        BigDecimal fillPrice =
                switch (cfg.fidelity()) {
                    case FAST -> {
                        if (snap.last() == null) yield null;
                        int sign = order.getSide() == OrderSide.BUY ? 1 : -1;
                        BigDecimal slippageFactor = cfg.marketSlippageBps()
                                .divide(BPS_DIVISOR, 8, RoundingMode.HALF_UP)
                                .multiply(BigDecimal.valueOf(sign));
                        yield snap.last().multiply(BigDecimal.ONE.add(slippageFactor));
                    }
                    case SPREAD -> order.getSide() == OrderSide.BUY ? snap.ask() : snap.bid();
                    case DEPTH -> walkDepth(order, snap);
                };
        if (fillPrice == null || fillPrice.signum() <= 0) {
            return Optional.empty();
        }
        return Optional.of(buildFill(order, fillPrice, order.remainingQty(), "taker", cfg.takerFeeRate(), snap.timestamp()));
    }

    private static Optional<Fill> matchLimit(Order order, MarketSnapshot snap, MatchConfig cfg) {
        if (order.getPrice() == null) return Optional.empty();
        boolean triggered =
                switch (cfg.fidelity()) {
                    case FAST -> {
                        if (snap.low() == null || snap.high() == null) yield false;
                        yield order.getSide() == OrderSide.BUY
                                ? snap.low().compareTo(order.getPrice()) <= 0
                                : snap.high().compareTo(order.getPrice()) >= 0;
                    }
                    case SPREAD, DEPTH -> {
                        if (snap.last() == null) yield false;
                        yield order.getSide() == OrderSide.BUY
                                ? snap.last().compareTo(order.getPrice()) <= 0
                                : snap.last().compareTo(order.getPrice()) >= 0;
                    }
                };
        if (!triggered) return Optional.empty();
        return Optional.of(buildFill(order, order.getPrice(), order.remainingQty(), "maker", cfg.makerFeeRate(), snap.timestamp()));
    }

    /** Walk-the-book 计算市价单的 VWAP。买单走 asks，卖单走 bids。 */
    private static BigDecimal walkDepth(Order order, MarketSnapshot snap) {
        List<PriceLevel> levels = order.getSide() == OrderSide.BUY ? snap.asks() : snap.bids();
        if (levels == null || levels.isEmpty()) return null;
        BigDecimal remainQty = order.remainingQty();
        BigDecimal totalCost = BigDecimal.ZERO;
        BigDecimal lastPrice = snap.last();
        for (PriceLevel level : levels) {
            BigDecimal take = remainQty.min(level.qty());
            totalCost = totalCost.add(take.multiply(level.price()));
            remainQty = remainQty.subtract(take);
            lastPrice = level.price();
            if (remainQty.signum() == 0) break;
        }
        if (remainQty.signum() > 0) {
            // 流动性不够，剩余按最后一档价格估算（保守）
            if (lastPrice == null) return null;
            totalCost = totalCost.add(remainQty.multiply(lastPrice));
        }
        return totalCost.divide(order.remainingQty(), 8, RoundingMode.HALF_UP);
    }

    private static Fill buildFill(
            Order order,
            BigDecimal price,
            BigDecimal qty,
            String liquidity,
            BigDecimal feeRate,
            Instant timestamp) {
        BigDecimal fee = price.multiply(qty).multiply(feeRate).setScale(8, RoundingMode.HALF_UP);
        // 手续费币种：买单收 base，卖单收 quote。简化：feeCurrency 在 Backtest/Paper 不严格区分，
        // 用 symbol 的 quote 部分（如 BTC/USDT → USDT）作为默认值。
        String feeCurrency = inferFeeCurrency(order.getSymbol(), order.getSide());
        return Fill.create(
                order.getId() != null ? order.getId() : 0L,
                order.getAccountId(),
                order.getSymbol(),
                order.getSide(),
                price.setScale(8, RoundingMode.HALF_UP),
                qty,
                fee,
                feeCurrency,
                liquidity,
                UUID.randomUUID().toString(),
                timestamp != null ? timestamp : Instant.now());
    }

    private static String inferFeeCurrency(String symbol, OrderSide side) {
        if (symbol == null) return null;
        int slash = symbol.indexOf('/');
        if (slash <= 0 || slash >= symbol.length() - 1) return null;
        // BUY: 用 quote 抵扣（如 USDT）；SELL: 用 quote 收取（如 USDT）
        // 简化：统一 quote（实盘 maker/taker rebate 由交易所决定，Backtest/Paper 不细分）
        return symbol.substring(slash + 1);
    }
}
