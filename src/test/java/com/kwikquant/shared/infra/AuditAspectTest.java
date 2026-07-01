package com.kwikquant.shared.infra;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class AuditAspectTest {

    private final List<AuditEntry> saved = new ArrayList<>();
    private final AuditRepository repository = saved::add;
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private Auditable auditable;
    private ProceedingJoinPoint pjp;
    private MethodSignature signature;

    @BeforeEach
    void setUp() throws Throwable {
        saved.clear();
        MDC.put(MdcKeys.TRACE_ID, "test-trace");

        auditable = mock(Auditable.class);
        when(auditable.action()).thenReturn("ORDER_SUBMITTED");
        when(auditable.targetType()).thenReturn("order");
        when(auditable.targetId()).thenReturn("");

        signature = mock(MethodSignature.class);
        when(signature.getMethod()).thenReturn(AuditAspectTest.class.getDeclaredMethod("dummyMethod"));

        pjp = mock(ProceedingJoinPoint.class);
        when(pjp.proceed()).thenReturn("ok");
        when(pjp.getSignature()).thenReturn(signature);
        when(pjp.getArgs()).thenReturn(new Object[] {});
    }

    @SuppressWarnings("unused")
    private void dummyMethod() {}

    @SuppressWarnings("unused")
    private void methodWithOrderId(String orderId) {}

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

    @Test
    void targetIdResolvedFromSpelExpression() throws Throwable {
        when(auditable.targetId()).thenReturn("#orderId");
        Method method = AuditAspectTest.class.getDeclaredMethod("methodWithOrderId", String.class);
        when(signature.getMethod()).thenReturn(method);
        when(pjp.getArgs()).thenReturn(new Object[] {"ORD-12345"});

        AuditAspect aspect = new AuditAspect(repository, mock(AuditExecutor.class), meterRegistry, false);
        aspect.around(pjp, auditable);

        assertEquals(1, saved.size());
        assertEquals("ORD-12345", saved.getFirst().targetId());
    }

    @Test
    void targetIdFallsBackToUnknownWhenSpelEmpty() throws Throwable {
        when(auditable.targetId()).thenReturn("");
        AuditAspect aspect = new AuditAspect(repository, mock(AuditExecutor.class), meterRegistry, false);

        aspect.around(pjp, auditable);

        assertEquals(1, saved.size());
        assertEquals("unknown", saved.getFirst().targetId());
    }

    @Test
    void targetIdFallsBackToUnknownWhenSpelEvaluationFails() throws Throwable {
        // #orderId is a String; calling .id() on it throws SpelEvaluationException → caught → null → "unknown"
        when(auditable.targetId()).thenReturn("#orderId.id()");
        Method method = AuditAspectTest.class.getDeclaredMethod("methodWithOrderId", String.class);
        when(signature.getMethod()).thenReturn(method);
        when(pjp.getArgs()).thenReturn(new Object[] {"ORD-12345"});

        AuditAspect aspect = new AuditAspect(repository, mock(AuditExecutor.class), meterRegistry, false);
        aspect.around(pjp, auditable);

        // SpEL failure must not break the audit — entry still saved with SUCCESS and "unknown" targetId
        assertEquals(1, saved.size());
        assertEquals("SUCCESS", saved.getFirst().status());
        assertEquals("unknown", saved.getFirst().targetId());
    }

    @Test
    void asyncNonCritical_usesExecutor() throws Throwable {
        // async=true + non-critical action → persistAsync path
        AuditExecutor executor = mock(AuditExecutor.class);
        when(auditable.action()).thenReturn("ORDER_SUBMITTED"); // non-critical
        AuditAspect aspect = new AuditAspect(repository, executor, meterRegistry, true);

        aspect.around(pjp, auditable);

        // Should delegate to executor.submit (not sync save)
        verify(executor).submit(any(Runnable.class));
    }

    @Test
    void asyncRejected_fallsBackToSync() throws Throwable {
        // async=true + executor rejects → fallback to sync
        AuditExecutor executor = mock(AuditExecutor.class);
        doThrow(new RejectedExecutionException("queue full")).when(executor).submit(any(Runnable.class));
        when(auditable.action()).thenReturn("ORDER_SUBMITTED"); // non-critical
        AuditAspect aspect = new AuditAspect(repository, executor, meterRegistry, true);

        aspect.around(pjp, auditable);

        // Fallback to sync → entry saved directly
        assertEquals(1, saved.size());
    }

    @Test
    void criticalSyncSaveFails_throwsCriticalAuditException() throws Throwable {
        // critical action + sync + save fails + no business error → CriticalAuditException
        AuditRepository failRepo = entry -> { throw new RuntimeException("DB down"); };
        when(auditable.action()).thenReturn("RISK_REJECTED"); // critical
        AuditAspect aspect = new AuditAspect(failRepo, mock(AuditExecutor.class), meterRegistry, false);

        assertThrows(CriticalAuditException.class, () -> aspect.around(pjp, auditable));
    }

    @Test
    void criticalSyncSaveFails_withBusinessError_doesNotThrow() throws Throwable {
        // critical action + sync + save fails + business error exists → log only, no throw
        AuditRepository failRepo = entry -> { throw new RuntimeException("DB down"); };
        when(auditable.action()).thenReturn("RISK_REJECTED"); // critical
        when(pjp.proceed()).thenThrow(new RuntimeException("business failed"));
        AuditAspect aspect = new AuditAspect(failRepo, mock(AuditExecutor.class), meterRegistry, false);

        // Business error propagates, but CriticalAuditException is NOT thrown
        assertThrows(RuntimeException.class, () -> aspect.around(pjp, auditable));
    }
}
