package com.kwikquant.trading.interfaces;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 持仓响应 DTO。
 */
public record PositionDto(
        Long positionId,
        Long accountId,
        String symbol,
        String side,
        BigDecimal qty,
        BigDecimal avgEntryPrice,
        BigDecimal realizedPnl,
        Long version,
        Instant updatedAt) {}
