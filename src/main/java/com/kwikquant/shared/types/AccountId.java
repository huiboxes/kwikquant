package com.kwikquant.shared.types;

public record AccountId(Long value) {
    public AccountId {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException("AccountId must be positive, got: " + value);
        }
    }
}
