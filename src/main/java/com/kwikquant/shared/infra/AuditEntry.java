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

    public AuditEntry {
        Objects.requireNonNull(action);
        Objects.requireNonNull(targetType);
        Objects.requireNonNull(status);
        if (!"SUCCESS".equals(status) && !"FAILED".equals(status)) {
            throw new IllegalArgumentException("status must be SUCCESS or FAILED");
        }
        Objects.requireNonNull(createdAt);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        actorUserId = actorUserId != null ? actorUserId : "anonymous";
        targetId = targetId != null ? targetId : "unknown";
    }
}
