package com.kwikquant.shared.infra;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.Ordered;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.annotation.Order;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Audit aspect：拦截 {@link Auditable}，包装 SUCCESS/FAILED 记录。
 *
 * <p>{@code @Order(LOWEST_PRECEDENCE - 1)} 让 AuditAspect 位于 {@code @Transactional} aspect 之外
 * （tx aspect 默认 LOWEST_PRECEDENCE）—— tx 先 begin/commit/rollback 完成，再由 AuditAspect 观测最终结果，
 * 避免 "audit 记 SUCCESS 但事务后续 commit 抛异常回滚" 的合规漏审场景。
 */
@Aspect
@Order(Ordered.LOWEST_PRECEDENCE - 1)
public class AuditAspect {

    private static final Logger log = LoggerFactory.getLogger(AuditAspect.class);
    private static final SpelExpressionParser SPEL_PARSER = new SpelExpressionParser();
    private static final ParameterNameDiscoverer PARAM_DISCOVERER = new DefaultParameterNameDiscoverer();

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
            AuditEntry entry = buildEntry(pjp, auditable, businessError, durationMs);
            persist(entry, auditable.action(), businessError);
        }
    }

    private AuditEntry buildEntry(ProceedingJoinPoint pjp, Auditable auditable, Throwable error, long durationMs) {
        String status = error == null ? AuditEntry.STATUS_SUCCESS : AuditEntry.STATUS_FAILED;
        String errorMessage = error != null ? error.getMessage() : null;
        String actorUserId = resolveActorUserId();
        String targetId = resolveTargetId(pjp, auditable.targetId());

        return new AuditEntry(
                actorUserId,
                auditable.action(),
                auditable.targetType(),
                targetId,
                MDC.get(MdcKeys.TRACE_ID),
                status,
                errorMessage,
                Map.of("durationMs", durationMs),
                Instant.now());
    }

    private String resolveActorUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !SecurityUtils.ANONYMOUS_PRINCIPAL.equals(auth.getPrincipal())) {
            return auth.getName();
        }
        return "anonymous";
    }

    private String resolveTargetId(ProceedingJoinPoint pjp, String spelExpression) {
        if (spelExpression == null || spelExpression.isBlank()) {
            return null;
        }
        try {
            MethodSignature sig = (MethodSignature) pjp.getSignature();
            String[] paramNames = PARAM_DISCOVERER.getParameterNames(sig.getMethod());
            if (paramNames == null) {
                return null;
            }
            EvaluationContext ctx = new StandardEvaluationContext();
            Object[] args = pjp.getArgs();
            for (int i = 0; i < paramNames.length; i++) {
                ((StandardEvaluationContext) ctx).setVariable(paramNames[i], args[i]);
            }
            Object value = SPEL_PARSER.parseExpression(spelExpression).getValue(ctx);
            return value != null ? value.toString() : null;
        } catch (Exception e) {
            log.warn("Failed to resolve targetId SpEL '{}': {}", spelExpression, e.getMessage());
            return null;
        }
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
            recordMetric(action, AuditEntry.STATUS_FAILED, "emit_failed");
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
                    recordMetric(action, AuditEntry.STATUS_FAILED, "emit_failed");
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
