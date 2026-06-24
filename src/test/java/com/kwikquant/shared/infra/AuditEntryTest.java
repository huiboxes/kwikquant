package com.kwikquant.shared.infra;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AuditEntryTest {

    @Test
    void validSuccessEntry() {
        AuditEntry entry = new AuditEntry(
                "123",
                "ORDER_SUBMITTED",
                "order",
                "456",
                "trace-1",
                "SUCCESS",
                null,
                Map.of("durationMs", 50L),
                Instant.now());
        assertEquals("123", entry.actorUserId());
        assertEquals("SUCCESS", entry.status());
    }

    @Test
    void nullActorDefaultsToAnonymous() {
        AuditEntry entry = new AuditEntry(null, "LOGIN", "user", null, null, "SUCCESS", null, null, Instant.now());
        assertEquals("anonymous", entry.actorUserId());
        assertEquals("unknown", entry.targetId());
        assertEquals(Map.of(), entry.metadata());
    }

    @Test
    void invalidStatusThrows() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new AuditEntry("1", "TEST", "test", null, null, "INVALID", null, null, Instant.now()));
    }

    @Test
    void failedEntryWithError() {
        AuditEntry entry = new AuditEntry(
                "1",
                "ORDER_SUBMITTED",
                "order",
                "789",
                "trace-2",
                "FAILED",
                "insufficient margin",
                null,
                Instant.now());
        assertEquals("FAILED", entry.status());
        assertEquals("insufficient margin", entry.error());
    }
}
