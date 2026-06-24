package com.kwikquant.shared.types;

import java.util.Set;

public enum OrderStatus {
    NEW,
    PENDING_NEW,
    SUBMITTED,
    EXCHANGE_ACCEPTED,
    EXCHANGE_REJECTED,
    INTERNAL_REJECTED,
    PARTIALLY_FILLED,
    FILLED,
    CANCEL_REQUESTED,
    CANCELED,
    EXPIRED;

    public Set<OrderStatus> allowedTransitions() {
        return switch (this) {
            case NEW -> Set.of(PENDING_NEW, INTERNAL_REJECTED);
            case PENDING_NEW -> Set.of(SUBMITTED, INTERNAL_REJECTED);
            case SUBMITTED -> Set.of(EXCHANGE_ACCEPTED, EXCHANGE_REJECTED, CANCEL_REQUESTED);
            case EXCHANGE_ACCEPTED -> Set.of(PARTIALLY_FILLED, FILLED, CANCEL_REQUESTED, EXPIRED);
            case PARTIALLY_FILLED -> Set.of(FILLED, CANCEL_REQUESTED);
            case CANCEL_REQUESTED -> Set.of(CANCELED, PARTIALLY_FILLED, FILLED, EXCHANGE_ACCEPTED);
            case FILLED, CANCELED, EXPIRED, EXCHANGE_REJECTED, INTERNAL_REJECTED -> Set.of();
        };
    }

    public boolean canTransitionTo(OrderStatus target) {
        return allowedTransitions().contains(target);
    }

    public boolean isTerminal() {
        return allowedTransitions().isEmpty();
    }
}
