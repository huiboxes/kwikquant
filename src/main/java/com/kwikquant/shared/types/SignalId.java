package com.kwikquant.shared.types;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

public record SignalId(UUID value) {
    public SignalId {
        Objects.requireNonNull(value, "SignalId must not be null");
    }

    public static SignalId deterministic(String seed) {
        return new SignalId(UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)));
    }
}
