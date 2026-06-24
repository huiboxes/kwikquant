package com.kwikquant.shared.types;

public record ExchangeOrderId(String value) {
    private static final int MAX_LENGTH = 128;

    public ExchangeOrderId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("ExchangeOrderId must not be blank");
        }
        if (value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "ExchangeOrderId must not exceed " + MAX_LENGTH + " characters, got: " + value.length());
        }
    }
}
