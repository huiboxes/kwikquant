package com.kwikquant.shared.types;

public record OrderId(Long value) {
    public OrderId {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException("OrderId must be positive, got: " + value);
        }
    }
}
