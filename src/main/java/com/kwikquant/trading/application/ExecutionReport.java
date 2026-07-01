package com.kwikquant.trading.application;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 成交回报。Executor 撮合产物 → ExecutionService.processExecutionReport(report)。
 *
 * <p>{@code externalFillId} 是 §1.4 幂等键的一部分（与 accountId 联合）。
 */
public record ExecutionReport(
        long orderId,
        String externalFillId,
        BigDecimal price,
        BigDecimal qty,
        BigDecimal fee,
        String feeCurrency,
        String liquidity,
        Instant filledAt) {}
