package com.kwikquant.shared.infra;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class AuditAspectTest {

    private final List<AuditEntry> saved = new ArrayList<>();
    private final AuditRepository repository = saved::add;
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private Auditable auditable;
    private ProceedingJoinPoint pjp;

    @BeforeEach
    void setUp() throws Throwable {
        saved.clear();
        MDC.put(MdcKeys.TRACE_ID, "test-trace");

        auditable = mock(Auditable.class);
        when(auditable.action()).thenReturn("ORDER_SUBMITTED");
        when(auditable.targetType()).thenReturn("order");

        pjp = mock(ProceedingJoinPoint.class);
        when(pjp.proceed()).thenReturn("ok");
    }

    @Test
    void syncSuccessPath() throws Throwable {
        AuditAspect aspect = new AuditAspect(repository, mock(AuditExecutor.class), meterRegistry, false);

        Object result = aspect.around(pjp, auditable);

        assertEquals("ok", result);
        assertEquals(1, saved.size());
        assertEquals("SUCCESS", saved.getFirst().status());
        assertEquals("ORDER_SUBMITTED", saved.getFirst().action());
        assertEquals("test-trace", saved.getFirst().traceId());
    }

    @Test
    void syncFailurePath() throws Throwable {
        when(pjp.proceed()).thenThrow(new RuntimeException("boom"));
        AuditAspect aspect = new AuditAspect(repository, mock(AuditExecutor.class), meterRegistry, false);

        assertThrows(RuntimeException.class, () -> aspect.around(pjp, auditable));

        assertEquals(1, saved.size());
        assertEquals("FAILED", saved.getFirst().status());
        assertEquals("boom", saved.getFirst().error());
    }

    @Test
    void criticalActionThrowsOnSaveFailure() throws Throwable {
        when(auditable.action()).thenReturn("RISK_BYPASSED");
        AuditRepository failingRepo = e -> {
            throw new RuntimeException("db down");
        };
        AuditAspect aspect = new AuditAspect(failingRepo, mock(AuditExecutor.class), meterRegistry, false);

        assertThrows(CriticalAuditException.class, () -> aspect.around(pjp, auditable));
    }

    @Test
    void criticalActionDoesNotThrowWhenBusinessAlreadyFailed() throws Throwable {
        when(auditable.action()).thenReturn("RISK_BYPASSED");
        when(pjp.proceed()).thenThrow(new RuntimeException("business error"));
        AuditRepository failingRepo = e -> {
            throw new RuntimeException("db down");
        };
        AuditAspect aspect = new AuditAspect(failingRepo, mock(AuditExecutor.class), meterRegistry, false);

        RuntimeException ex = assertThrows(RuntimeException.class, () -> aspect.around(pjp, auditable));
        assertEquals("business error", ex.getMessage());
    }

    @Test
    void asyncPathDelegatesToExecutor() throws Throwable {
        AuditExecutor executor = mock(AuditExecutor.class);
        AuditAspect aspect = new AuditAspect(repository, executor, meterRegistry, true);

        aspect.around(pjp, auditable);

        verify(executor).submit(any(Runnable.class));
        assertTrue(saved.isEmpty());
    }

    @Test
    void asyncRejectionFallsBackToSync() throws Throwable {
        AuditExecutor executor = mock(AuditExecutor.class);
        doThrow(new RejectedExecutionException()).when(executor).submit(any());
        AuditAspect aspect = new AuditAspect(repository, executor, meterRegistry, true);

        aspect.around(pjp, auditable);

        assertEquals(1, saved.size());
        assertEquals("SUCCESS", saved.getFirst().status());
    }
}
