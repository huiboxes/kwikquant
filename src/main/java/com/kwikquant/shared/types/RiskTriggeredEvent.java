package com.kwikquant.shared.types;

import java.time.Instant;
import java.util.Objects;

/**
 * Domain event published when a risk check rejects an order.
 *
 * <p>{@code strategyId} may be {@code null} in Wave 5 where orders are submitted manually
 * (no strategy context). {@code userId} identifies the owning user for audit/notification.
 * {@code orderId} identifies the rejected order so the frontend can correlate the
 * WebSocket notification with the submitted order.
 */
public record RiskTriggeredEvent(
        long userId, OrderId orderId, AccountId accountId, StrategyId strategyId, String reason, Instant timestamp) {

    public RiskTriggeredEvent {
        Objects.requireNonNull(orderId);
        Objects.requireNonNull(accountId);
        // strategyId can be null (v5, no strategy yet)
        Objects.requireNonNull(reason);
        Objects.requireNonNull(timestamp);
    }
}
