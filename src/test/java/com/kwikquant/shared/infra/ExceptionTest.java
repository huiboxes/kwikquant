package com.kwikquant.shared.infra;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ExceptionTest {

    @Test
    void resourceNotFoundException() {
        var ex = new ResourceNotFoundException("order");
        assertEquals("order not found", ex.getMessage());
    }

    @Test
    void resourceNotFoundExceptionWithId() {
        var ex = new ResourceNotFoundException("order", 123L);
        assertEquals("order not found: 123", ex.getMessage());
    }

    @Test
    void ownershipViolationException() {
        var ex = new OwnershipViolationException("account");
        assertTrue(ex.getMessage().contains("account"));
    }

    @Test
    void exchangeExceptionRetryable() {
        var ex = new ExchangeException("timeout", true);
        assertTrue(ex.isRetryable());
        assertEquals("timeout", ex.getMessage());
    }

    @Test
    void exchangeExceptionNotRetryable() {
        var ex = new ExchangeException("auth failed", new RuntimeException(), false);
        assertFalse(ex.isRetryable());
        assertNotNull(ex.getCause());
    }

    @Test
    void criticalAuditException() {
        var cause = new RuntimeException("db down");
        var ex = new CriticalAuditException("RISK_BYPASSED", cause);
        assertTrue(ex.getMessage().contains("RISK_BYPASSED"));
        assertSame(cause, ex.getCause());
    }

    @Test
    void apiResponseOk() {
        var resp = ApiResponse.ok("data", "trace-1");
        assertEquals(0, resp.code());
        assertEquals("data", resp.data());
        assertEquals("trace-1", resp.traceId());
    }

    @Test
    void apiResponseError() {
        var resp = ApiResponse.error(1001, "unauthorized", "trace-2");
        assertEquals(1001, resp.code());
        assertNull(resp.data());
    }
}
