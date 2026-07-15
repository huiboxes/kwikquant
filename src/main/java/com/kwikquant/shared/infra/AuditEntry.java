package com.kwikquant.shared.infra;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public record AuditEntry(
        String actorUserId,
        String action,
        String targetType,
        String targetId,
        String traceId,
        String status,
        String error,
        Map<String, Object> metadata,
        Instant createdAt) {

    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";

    public AuditEntry {
        Objects.requireNonNull(action);
        Objects.requireNonNull(targetType);
        Objects.requireNonNull(status);
        if (!STATUS_SUCCESS.equals(status) && !STATUS_FAILED.equals(status)) {
            throw new IllegalArgumentException("status must be " + STATUS_SUCCESS + " or " + STATUS_FAILED);
        }
        Objects.requireNonNull(createdAt);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        actorUserId = actorUserId != null ? actorUserId : "anonymous";
        targetId = targetId != null ? targetId : "unknown";
    }
}
