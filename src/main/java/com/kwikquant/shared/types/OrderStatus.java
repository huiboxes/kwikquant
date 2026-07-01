package com.kwikquant.shared.types;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public enum OrderStatus {
    NEW,
    PENDING_NEW,
    SUBMITTED,
    PARTIALLY_FILLED,
    FILLED,
    PENDING_CANCEL,
    CANCELLED,
    REJECTED,
    EXPIRED;

    private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED = Map.of(
            NEW, EnumSet.of(PENDING_NEW, REJECTED),
            PENDING_NEW, EnumSet.of(SUBMITTED, FILLED, PARTIALLY_FILLED, REJECTED, EXPIRED),
            SUBMITTED, EnumSet.of(PARTIALLY_FILLED, FILLED, PENDING_CANCEL, CANCELLED, REJECTED, EXPIRED),
            PARTIALLY_FILLED, EnumSet.of(FILLED, PENDING_CANCEL, CANCELLED, REJECTED, EXPIRED),
            PENDING_CANCEL, EnumSet.of(CANCELLED, FILLED),
            FILLED, EnumSet.noneOf(OrderStatus.class),
            CANCELLED, EnumSet.noneOf(OrderStatus.class),
            REJECTED, EnumSet.noneOf(OrderStatus.class),
            EXPIRED, EnumSet.noneOf(OrderStatus.class));

    public Set<OrderStatus> allowedTransitions() {
        return ALLOWED.getOrDefault(this, Set.of());
    }

    public boolean canTransitionTo(OrderStatus target) {
        return allowedTransitions().contains(target);
    }

    public boolean isTerminal() {
        return this == FILLED || this == CANCELLED || this == REJECTED || this == EXPIRED;
    }
}
