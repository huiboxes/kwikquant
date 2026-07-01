package com.kwikquant.trading.domain;

import com.kwikquant.shared.types.OrderStatus;

public class IllegalOrderStateTransitionException extends RuntimeException {
    private final OrderStatus from;
    private final OrderStatus to;

    public IllegalOrderStateTransitionException(OrderStatus from, OrderStatus to) {
        super("Illegal order state transition: " + from + " -> " + to);
        this.from = from;
        this.to = to;
    }

    public OrderStatus from() {
        return from;
    }

    public OrderStatus to() {
        return to;
    }
}
