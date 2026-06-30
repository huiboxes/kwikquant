package com.kwikquant.trading.interfaces;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 订单详情响应 DTO。
 */
public record OrderDetailDto(
        Long orderId,
        Long accountId,
        String symbol,
        String side,
        String orderType,
        BigDecimal amount,
        BigDecimal price,
        BigDecimal stopPrice,
        String timeInForce,
        Instant expireAt,
        String status,
        BigDecimal filledQty,
        BigDecimal filledAvgPrice,
        String clientOrderId,
        String exchangeOrderId,
        Long version,
        Instant createdAt,
        Instant updatedAt) {}
