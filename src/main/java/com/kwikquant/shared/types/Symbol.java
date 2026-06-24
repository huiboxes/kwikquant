package com.kwikquant.shared.types;

public record Symbol(String value) {
    public Symbol {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Symbol must not be blank");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
