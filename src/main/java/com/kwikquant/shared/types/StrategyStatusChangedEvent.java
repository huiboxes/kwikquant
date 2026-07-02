package com.kwikquant.shared.types;

import java.time.Instant;
import java.util.Objects;

/**
 * Domain event published when a strategy transitions between statuses.
 *
 * <p>{@code userId} identifies the owning user for notification routing. The notification module
 * depends only on shared, so userId must be carried in the event rather than resolved via account
 * lookup. Mirrors {@link OrderStatusChangedEvent} (typed ID + enum status).
 */
public record StrategyStatusChangedEvent(
        long userId,
        StrategyId strategyId,
        StrategyStatus previousStatus,
        StrategyStatus newStatus,
        Instant timestamp) {

    public StrategyStatusChangedEvent {
        Objects.requireNonNull(strategyId);
        Objects.requireNonNull(previousStatus);
        Objects.requireNonNull(newStatus);
        Objects.requireNonNull(timestamp);
    }
}
