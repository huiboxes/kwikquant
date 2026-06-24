package com.kwikquant.shared.infra;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

@Aspect
public class AuditAspect {

    private static final Logger log = LoggerFactory.getLogger(AuditAspect.class);

    private final AuditRepository repository;
    private final AuditExecutor executor;
    private final MeterRegistry meterRegistry;
    private final boolean async;

    public AuditAspect(AuditRepository repository, AuditExecutor executor, MeterRegistry meterRegistry, boolean async) {
        this.repository = repository;
        this.executor = executor;
        this.meterRegistry = meterRegistry;
        this.async = async;
    }

    @Around("@annotation(auditable)")
    public Object around(ProceedingJoinPoint pjp, Auditable auditable) throws Throwable {
        Object result = null;
        Throwable businessError = null;
        long startNanos = System.nanoTime();

        try {
            result = pjp.proceed();
            return result;
        } catch (Throwable ex) {
            businessError = ex;
            throw ex;
        } finally {
            long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
            AuditEntry entry = buildEntry(auditable, businessError, durationMs);
            persist(entry, auditable.action(), businessError);
        }
    }

    private AuditEntry buildEntry(Auditable auditable, Throwable error, long durationMs) {
        String status = error == null ? "SUCCESS" : "FAILED";
        String errorMessage = error != null ? error.getMessage() : null;
        String actorUserId = resolveActorUserId();

        return new AuditEntry(
                actorUserId,
                auditable.action(),
                auditable.targetType(),
                null,
                MDC.get(MdcKeys.TRACE_ID),
                status,
                errorMessage,
                Map.of("durationMs", durationMs),
                Instant.now());
    }

    private String resolveActorUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
            return auth.getName();
        }
        return "anonymous";
    }

    private void persist(AuditEntry entry, String action, Throwable businessError) {
        boolean critical = CriticalAuditActions.isCritical(action);
        if (critical || !async) {
            persistSync(entry, action, critical, businessError);
        } else {
            persistAsync(entry, action);
        }
    }

    private void persistSync(AuditEntry entry, String action, boolean critical, Throwable businessError) {
        try {
            repository.save(entry);
            recordMetric(action, entry.status(), "saved");
        } catch (Exception saveError) {
            if (critical && businessError == null) {
                throw new CriticalAuditException(action, saveError);
            }
            recordMetric(action, "FAILED", "emit_failed");
            log.error("Audit save failed for action={}: {}", action, saveError.getMessage(), saveError);
        }
    }

    private void persistAsync(AuditEntry entry, String action) {
        Map<String, String> mdcSnapshot = MDC.getCopyOfContextMap();
        try {
            executor.submit(() -> {
                if (mdcSnapshot != null) MDC.setContextMap(mdcSnapshot);
                try {
                    repository.save(entry);
                    recordMetric(action, entry.status(), "saved");
                } catch (Exception saveError) {
                    recordMetric(action, "FAILED", "emit_failed");
                    log.error("Async audit save failed for action={}: {}", action, saveError.getMessage(), saveError);
                } finally {
                    MDC.clear();
                }
            });
        } catch (RejectedExecutionException rejected) {
            log.warn("Audit executor queue full for action={}, falling back to sync", action);
            persistSync(entry, action, false, null);
        }
    }

    private void recordMetric(String action, String status, String outcome) {
        Counter.builder("audit_" + outcome + "_total")
                .tag("action", action)
                .tag("status", status)
                .register(meterRegistry)
                .increment();
    }
}
