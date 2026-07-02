package com.kwikquant.report.interfaces;

import java.math.BigDecimal;
import java.time.Instant;

public record TradeHistoryDto(
        long orderId,
        long accountId,
        String symbol,
        String side,
        String orderType,
        BigDecimal amount,
        BigDecimal filledQty,
        BigDecimal filledAvgPrice,
        BigDecimal totalFee,
        BigDecimal totalVolume,
        String status,
        Instant createdAt,
        Instant updatedAt) {}
