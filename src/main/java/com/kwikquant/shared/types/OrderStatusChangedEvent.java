package com.kwikquant.shared.types;

import java.time.Instant;
import java.util.Objects;

public record OrderStatusChangedEvent(
        OrderId orderId, AccountId accountId, OrderStatus previousStatus, OrderStatus newStatus, Instant timestamp) {

    public OrderStatusChangedEvent {
        Objects.requireNonNull(orderId);
        Objects.requireNonNull(accountId);
        Objects.requireNonNull(previousStatus);
        Objects.requireNonNull(newStatus);
        Objects.requireNonNull(timestamp);
    }
}
