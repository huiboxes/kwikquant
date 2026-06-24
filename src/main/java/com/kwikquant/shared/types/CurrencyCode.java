package com.kwikquant.shared.types;

public record CurrencyCode(String value) {
    private static final String ISO_4217_ALPHA_PATTERN = "[A-Z]{3,8}";

    public CurrencyCode {
        if (value == null || !value.matches(ISO_4217_ALPHA_PATTERN)) {
            throw new IllegalArgumentException("CurrencyCode must be 3-8 uppercase letters, got: " + value);
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
