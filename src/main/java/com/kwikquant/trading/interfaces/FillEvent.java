package com.kwikquant.trading.interfaces;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * WebSocket 成交事件。
 */
public record FillEvent(
        String eventType,
        Long fillId,
        Long orderId,
        Long accountId,
        String symbol,
        String side,
        BigDecimal price,
        BigDecimal qty,
        BigDecimal fee,
        String feeCurrency,
        String liquidity,
        Instant filledAt) {

    public static FillEvent of(FillDto fill) {
        return new FillEvent(
                "NEW_FILL",
                fill.fillId(),
                fill.orderId(),
                fill.accountId(),
                fill.symbol(),
                fill.side(),
                fill.price(),
                fill.qty(),
                fill.fee(),
                fill.feeCurrency(),
                fill.liquidity(),
                fill.filledAt());
    }
}
