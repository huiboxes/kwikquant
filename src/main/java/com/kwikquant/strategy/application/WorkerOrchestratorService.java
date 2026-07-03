package com.kwikquant.strategy.application;

import com.kwikquant.shared.infra.WorkerTokenService;
import com.kwikquant.strategy.domain.StrategyCode;
import com.kwikquant.strategy.domain.StrategyDefinition;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Python Worker 容器生命周期编排：拉起、健康检查、崩溃重启、优雅销毁。
 *
 * <p><b>循环依赖打破（架构师决策）</b>：WOS 不直接依赖 {@link StrategyLifecycleService}。健康检查连续失败
 * 阈值后发布 {@link WorkerMarkErrorEvent}，由 LifecycleService 监听调 {@code markError}。这样
 * LifecycleService→WOS 单向依赖（start/stop），WOS→事件→LifecycleService 反向用事件，无构造期循环。
 *
 * <p><b>内存 Registry</b>：{@code ConcurrentHashMap<Long, WorkerStatus>}，不持久化。应用重启丢失，
 * 由 {@link #reconcileRunningStrategies()}（{@link ApplicationReadyEvent}）遍历 {@code status=RUNNING} 策略重建。
 *
 * <p>Wave 6 只建 Java 侧编排逻辑；Python Worker 容器本身 Wave 8 实现。
 */
@Service
public class WorkerOrchestratorService {

    private static final Logger log = LoggerFactory.getLogger(WorkerOrchestratorService.class);
    static final int MAX_FAILURES = 3;

    private final WorkerManager workerManager;
    private final StrategyCrudService crudService;
    private final StrategyCodeService codeService;
    private final ApplicationEventPublisher eventPublisher;
    private final WorkerTokenService workerTokenService;
    private final String apiBaseUrl;
    private final ConcurrentHashMap<Long, WorkerStatus> registry = new ConcurrentHashMap<>();

    public WorkerOrchestratorService(
            WorkerManager workerManager,
            StrategyCrudService crudService,
            StrategyCodeService codeService,
            ApplicationEventPublisher eventPublisher,
            WorkerTokenService workerTokenService,
            @Value("${kwikquant.worker.api-base-url:http://localhost:8080}") String apiBaseUrl) {
        this.workerManager = workerManager;
        this.crudService = crudService;
        this.codeService = codeService;
        this.eventPublisher = eventPublisher;
        this.workerTokenService = workerTokenService;
        this.apiBaseUrl = apiBaseUrl;
    }

    public void startWorker(StrategyDefinition strategy, StrategyCode code) {
        // 防孤儿：若已存在旧容器，先停掉
        WorkerStatus existing = registry.get(strategy.getId());
        if (existing != null) {
            stopContainerQuietly(existing.containerId());
        }
        WorkerConfig config = buildConfig(strategy, code);
        String containerId = workerManager.createAndStart(config);
        registry.put(strategy.getId(), new WorkerStatus(strategy.getId(), containerId, true, Instant.now(), 0));
    }

    public void stopWorker(long strategyId) {
        WorkerStatus st = registry.remove(strategyId);
        if (st == null) {
            // 幂等：未运行直接返回;仍尝试 revoke token,防 stop 无 start 的孤儿 token
            workerTokenService.revokeTokenForStrategy(strategyId);
            return;
        }
        stopContainerQuietly(st.containerId());
        workerTokenService.revokeTokenForStrategy(strategyId);
    }

    public WorkerStatus getWorkerStatus(long strategyId) {
        return registry.get(strategyId);
    }

    @Scheduled(fixedDelay = 30_000)
    public void healthCheckAll() {
        for (WorkerStatus st : List.copyOf(registry.values())) {
            try {
                if (workerManager.healthCheck(st.containerId())) {
                    registry.put(st.strategyId(), st.onHealthy(Instant.now()));
                } else {
                    handleUnhealthy(st);
                }
            } catch (Exception e) {
                log.warn("Health check exception for strategy {}", st.strategyId(), e);
                handleUnhealthy(st);
            }
        }
    }

    /** 应用重启后重建 RUNNING 策略的 Worker Registry（内存 Registry 不持久化）。 */
    @EventListener(ApplicationReadyEvent.class)
    public void reconcileRunningStrategies() {
        for (StrategyDefinition s : crudService.findRunningStrategies()) {
            try {
                StrategyCode code = codeService.getPublishedCode(s.getId());
                if (code == null) {
                    eventPublisher.publishEvent(
                            new WorkerMarkErrorEvent(s.getId(), "No published code during reconcile"));
                    continue;
                }
                startWorker(s, code);
                log.info("Reconciled worker for strategy {}", s.getId());
            } catch (Exception e) {
                log.error("Reconcile failed for strategy {}", s.getId(), e);
                eventPublisher.publishEvent(new WorkerMarkErrorEvent(s.getId(), "Reconcile failed: " + e.getMessage()));
            }
        }
    }

    private void handleUnhealthy(WorkerStatus st) {
        WorkerStatus failed = st.onUnhealthy(Instant.now());
        registry.put(st.strategyId(), failed);
        if (failed.consecutiveFailures() >= MAX_FAILURES) {
            stopContainerQuietly(failed.containerId());
            registry.remove(st.strategyId());
            workerTokenService.revokeTokenForStrategy(st.strategyId());
            eventPublisher.publishEvent(new WorkerMarkErrorEvent(
                    st.strategyId(), "Health check failed " + MAX_FAILURES + " consecutive times"));
        } else {
            restartStrategy(st.strategyId(), failed);
        }
    }

    private void restartStrategy(long strategyId, WorkerStatus failed) {
        try {
            stopContainerQuietly(failed.containerId());
            StrategyDefinition s = crudService.findById(strategyId);
            StrategyCode code = codeService.getPublishedCode(strategyId);
            if (code == null) {
                eventPublisher.publishEvent(new WorkerMarkErrorEvent(strategyId, "No published code for restart"));
                return;
            }
            WorkerConfig config = buildConfig(s, code);
            String newContainerId = workerManager.createAndStart(config);
            registry.put(strategyId, failed.withContainer(newContainerId, Instant.now()));
        } catch (Exception e) {
            log.error("Restart failed for strategy {}", strategyId, e);
            // 留作 failed 状态，下次健康检查继续累计 → 最终 markError
        }
    }

    private void stopContainerQuietly(String containerId) {
        try {
            workerManager.stop(containerId);
        } catch (Exception e) {
            log.debug("docker stop ignored for {}", containerId, e);
        }
        try {
            workerManager.remove(containerId);
        } catch (Exception e) {
            log.debug("docker rm ignored for {}", containerId, e);
        }
    }

    private WorkerConfig buildConfig(StrategyDefinition strategy, StrategyCode code) {
        // Wave 8 §3.7:token 由 WTS 签发,绑 strategyId+taskType=RUNNER,应用重启后 reconcile 重发。
        // WTS.issueToken 对同一 strategyId 重发时自动 revoke 旧 token(reissue 语义),
        // startWorker 替换旧容器场景不需额外 revoke。
        String token = workerTokenService.issueToken(strategy.getId(), "RUNNER");
        return WorkerConfig.forStrategy(strategy, code, apiBaseUrl, token);
    }
}
