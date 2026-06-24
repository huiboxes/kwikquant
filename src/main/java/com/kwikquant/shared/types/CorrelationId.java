package com.kwikquant.shared.types;

import java.util.Objects;
import java.util.UUID;

public record CorrelationId(UUID value) {
    public CorrelationId {
        Objects.requireNonNull(value, "CorrelationId must not be null");
    }

    public static CorrelationId random() {
        return new CorrelationId(UUID.randomUUID());
    }
}
