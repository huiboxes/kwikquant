package com.kwikquant.shared.types;

public record StrategyId(Long value) {
    public StrategyId {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException("StrategyId must be positive, got: " + value);
        }
    }
}
