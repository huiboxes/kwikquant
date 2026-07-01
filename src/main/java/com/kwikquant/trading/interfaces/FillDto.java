package com.kwikquant.trading.interfaces;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 成交记录响应 DTO。
 */
public record FillDto(
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
        String externalFillId,
        Instant filledAt) {}
