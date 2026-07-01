package com.kwikquant.trading.domain;

import com.kwikquant.shared.infra.ResourceNotFoundException;

public class OrderNotFoundException extends ResourceNotFoundException {
    private final long orderId;

    public OrderNotFoundException(long orderId) {
        super("Order", orderId);
        this.orderId = orderId;
    }

    public long orderId() {
        return orderId;
    }
}
