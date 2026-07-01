package com.kwikquant.risk.domain;

import com.kwikquant.shared.types.OrderSide;
import com.kwikquant.shared.types.OrderType;
import java.math.BigDecimal;

/**
 * Immutable request payload for a pre-trade risk check.
 *
 * @param orderId           internal order id
 * @param accountId         exchange account id
 * @param userId            owning user id
 * @param symbol            trading pair symbol, e.g. "BTC/USDT"
 * @param side              order side (BUY / SELL)
 * @param orderType         order type (MARKET / LIMIT / ...)
 * @param amount            order quantity
 * @param price             limit price (may be null for market orders)
 * @param notionalValue     estimated notional value in USDT (may be null if unavailable)
 * @param recentOrderCount  number of orders submitted by this account in the last 60s
 *                          (for ORDER_FREQUENCY; computed by TradingService so risk stays
 *                          free of trading-module dependencies)
 * @param requestId         idempotency key for the risk check
 */
public record RiskCheckRequest(
        long orderId,
        long accountId,
        long userId,
        String symbol,
        OrderSide side,
        OrderType orderType,
        BigDecimal amount,
        BigDecimal price,
        BigDecimal notionalValue,
        int recentOrderCount,
        String requestId) {}
