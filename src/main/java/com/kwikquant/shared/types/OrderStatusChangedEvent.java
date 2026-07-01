package com.kwikquant.shared.types;

import java.time.Instant;
import java.util.Objects;

/**
 * Domain event published when an order transitions between statuses.
 *
 * <p>{@code userId} identifies the owning user for notification routing.
 * The notification module depends only on shared, so userId must be
 * carried in the event rather than resolved via account lookup.
 */
public record OrderStatusChangedEvent(
        long userId,
        OrderId orderId,
        AccountId accountId,
        OrderStatus previousStatus,
        OrderStatus newStatus,
        Instant timestamp) {

    public OrderStatusChangedEvent {
        Objects.requireNonNull(orderId);
        Objects.requireNonNull(accountId);
        Objects.requireNonNull(previousStatus);
        Objects.requireNonNull(newStatus);
        Objects.requireNonNull(timestamp);
    }
}
