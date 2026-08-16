package com.kwikquant.strategy.application;

import com.kwikquant.shared.infra.WorkerTokenService;
import com.kwikquant.shared.types.StrategyStatus;
import com.kwikquant.strategy.domain.StrategyCode;
import com.kwikquant.strategy.domain.StrategyDefinition;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
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
 * <p><b>循环依赖打破</b>：WOS 不直接依赖 {@link StrategyLifecycleService}。健康检查连续失败
 * 阈值后发布 {@link WorkerMarkErrorEvent}，由 LifecycleService 监听调 {@code markError}。这样
 * LifecycleService→WOS 单向依赖（start/stop），WOS→事件→LifecycleService 反向用事件，无构造期循环。
 *
 * <p><b>内存 Registry</b>：{@code ConcurrentHashMap<Long, WorkerStatus>}，不持久化。应用重启丢失，
 * 由 {@link #reconcileRunningStrategies()}（{@link ApplicationReadyEvent}）遍历 {@code status=RUNNING} 策略重建。
 *
 * <p>Java 侧编排逻辑；Python Worker 容器独立实现。
 */
@Service
public class WorkerOrchestratorService {

    private static final Logger log = LoggerFactory.getLogger(WorkerOrchestratorService.class);
    private static final int MAX_FAILURES = 3;
    /** 连续失败达此阈值才 restart(首次失败先观察,防秒级 WS 抖动立即 restart 丢策略内存状态)。 */
    private static final int RESTART_THRESHOLD = 2;

    private static final long HEALTH_CHECK_INTERVAL_MS = 30_000;
    /** 孤儿容器 GC 间隔(扫 strategy-worker-* 对账 registry,删残留)。 */
    private static final long ORPHAN_GC_INTERVAL_MS = 5 * 60_000;

    private final WorkerManager workerManager;
    private final StrategyCrudService crudService;
    private final StrategyCodeService codeService;
    private final ApplicationEventPublisher eventPublisher;
    private final WorkerTokenService workerTokenService;
    private final String apiBaseUrl;
    private final ConcurrentHashMap<Long, WorkerStatus> registry = new ConcurrentHashMap<>();
    /**
     * 运行中 worker 的启动配置(含已 issue 的 serviceToken),供 bootstrap 端点拉取。
     * startContainer 时 put(先于 createAndStart,保证 worker 容器启动后 GET /worker/bootstrap 时 config 已就位);
     * stop/markError 时 remove(与 registry/token registry 生命周期一致)。不持久化,reconcile 重建。
     */
    private final ConcurrentHashMap<Long, WorkerConfig> configRegistry = new ConcurrentHashMap<>();
    /** per-strategyId 锁:串行化 start/stop/restart,防 healthCheckAll restart 与 HTTP start 并发致 docker run 同名冲突。 */
    private final ConcurrentHashMap<Long, ReentrantLock> strategyLocks = new ConcurrentHashMap<>();

    private ReentrantLock lockFor(long strategyId) {
        return strategyLocks.computeIfAbsent(strategyId, k -> new ReentrantLock());
    }

    public WorkerOrchestratorService(
            WorkerManager workerManager,
            StrategyCrudService crudService,
            StrategyCodeService codeService,
            ApplicationEventPublisher eventPublisher,
            WorkerTokenService workerTokenService,
            @Value("${kwikquant.worker.api-base-url}") String apiBaseUrl) {
        this.workerManager = workerManager;
        this.crudService = crudService;
        this.codeService = codeService;
        this.eventPublisher = eventPublisher;
        this.workerTokenService = workerTokenService;
        this.apiBaseUrl = apiBaseUrl;
    }

    public void startWorker(StrategyDefinition strategy, StrategyCode code) {
        ReentrantLock lock = lockFor(strategy.getId());
        lock.lock();
        try {
            // 防孤儿：若已存在旧容器，先停掉
            WorkerStatus existing = registry.get(strategy.getId());
            if (existing != null) {
                stopContainerQuietly(existing.containerId());
            }
            String containerId = startContainer(strategy, code);
            registry.put(strategy.getId(), new WorkerStatus(strategy.getId(), containerId, true, Instant.now(), 0));
        } finally {
            lock.unlock();
        }
    }

    public void stopWorker(long strategyId) {
        ReentrantLock lock = lockFor(strategyId);
        lock.lock();
        try {
            workerTokenService.revokeRunnerTokenForStrategy(strategyId);
            configRegistry.remove(strategyId);
            WorkerStatus st = registry.remove(strategyId);
            if (st == null) {
                // 幂等：未运行直接返回；RUNNER token 已先吊销，且不会影响并存回测
                return;
            }
            stopContainerQuietly(st.containerId());
        } finally {
            lock.unlock();
        }
    }

    public WorkerStatus getWorkerStatus(long strategyId) {
        return registry.get(strategyId);
    }

    /**
     * 供 bootstrap 端点:返回运行中 worker 的启动配置(含已 issue 的 serviceToken)。
     * strategy 未运行/已停返 null(bootstrap controller 抛 7307)。与 registry/token registry 同步 remove。
     */
    public WorkerConfig getWorkerConfig(long strategyId) {
        return configRegistry.get(strategyId);
    }

    @Scheduled(fixedDelay = HEALTH_CHECK_INTERVAL_MS)
    public void healthCheckAll() {
        for (WorkerStatus st : List.copyOf(registry.values())) {
            try {
                if (workerManager.healthCheck(st.containerId())) {
                    // 身份校验:仅当 registry 当前条目仍是本 containerId 才更新
                    // (防 stop 并发 remove 后盲 put 把已停策略复活回 registry)
                    registry.compute(
                            st.strategyId(),
                            (sid, cur) -> cur != null && cur.containerId().equals(st.containerId())
                                    ? cur.onHealthy(Instant.now())
                                    : cur);
                } else {
                    handleUnhealthy(st);
                }
            } catch (Exception e) {
                log.warn("Health check exception for strategy {}", st.strategyId(), e);
                handleUnhealthy(st);
            }
        }
    }

    /**
     * app 停机:停所有运行中 worker + 吊销 token(防 worker 持有效 RUNNER token 在容器里继续下单——
     * 资损级缺口:原实现无 @PreDestroy,正常停机不停 worker,容器带有效 token 继续跑直到自身崩溃)。
     * 不 mark DB STOPPED(停机临时,reconcile 重建 RUNNING);stopWorker 内 revoke + stop + remove。
     */
    @PreDestroy
    public void shutdown() {
        log.info("App shutting down: stopping {} worker container(s)", registry.size());
        for (long strategyId : List.copyOf(registry.keySet())) {
            try {
                stopWorker(strategyId);
            } catch (Exception e) {
                log.warn("Shutdown: stopWorker failed for strategy {}", strategyId, e);
            }
        }
    }

    /**
     * 孤儿容器 GC:扫所有 strategy-worker-* 容器,对账 registry,删 registry 无的(app 崩溃残留 /
     * markError 后未清)。tryLock 避与 start/stop 并发 race(否则可能删到 start 中刚 createAndStart
     * 但未 put registry 的容器)。fixedDelay 首次在 reconcile(@ApplicationReadyEvent)之后,不误删重建中。
     */
    @Scheduled(fixedDelay = ORPHAN_GC_INTERVAL_MS)
    public void cleanupOrphanContainers() {
        Set<String> live =
                registry.values().stream().map(WorkerStatus::containerId).collect(Collectors.toSet());
        for (String name : workerManager.listStrategyWorkerContainers()) {
            long strategyId = parseStrategyId(name);
            if (strategyId < 0) continue;
            ReentrantLock lock = lockFor(strategyId);
            if (!lock.tryLock()) continue; // start/stop 进行中,跳过避 race
            try {
                if (live.contains(name) || registry.containsKey(strategyId)) continue; // 在用
                log.warn("Removing orphan worker container not in registry: {}", name);
                workerManager.remove(name);
            } finally {
                lock.unlock();
            }
        }
    }

    /** 从容器名解析 strategyId(strategy-worker-42 → 42);非匹配返 -1。 */
    private long parseStrategyId(String containerName) {
        if (!containerName.startsWith(WorkerManager.CONTAINER_NAME_PREFIX)) return -1;
        try {
            return Long.parseLong(containerName.substring(WorkerManager.CONTAINER_NAME_PREFIX.length()));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 应用重启后重建 RUNNING 策略的 Worker Registry（内存 Registry 不持久化）。 */
    @EventListener(ApplicationReadyEvent.class)
    public void reconcileRunningStrategies() {
        for (StrategyDefinition s : crudService.findRunningStrategies()) {
            try {
                // 重构后:RUNNING strategy 必须有 exchange_account_id(start 选账户绑 token);
                // 历史无账户的 RUNNING 标 ERROR 不重建(去 UNIQUE 后同 exchange 多账户,需显式选账户)
                Long accountId = s.getExchangeAccountId();
                if (accountId == null || accountId == 0) {
                    eventPublisher.publishEvent(new WorkerMarkErrorEvent(
                            s.getId(), "no exchange_account_id bound; re-start with an account"));
                    continue;
                }
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
        if (failed.consecutiveFailures() >= MAX_FAILURES) {
            registry.compute(st.strategyId(), (sid, current) -> {
                if (current != null && current.containerId().equals(st.containerId())) {
                    stopContainerQuietly(current.containerId());
                    workerTokenService.revokeRunnerTokenForStrategy(sid);
                    configRegistry.remove(sid);
                    return null;
                }
                return current;
            });
            eventPublisher.publishEvent(new WorkerMarkErrorEvent(
                    st.strategyId(), "Health check failed " + MAX_FAILURES + " consecutive times"));
        } else {
            // 身份校验:仅当 registry 当前条目仍是本 containerId 才更新
            // (防 stop 并发 remove 后盲 put 把已停策略复活回 registry → restartStrategy 拉僵尸)
            registry.compute(
                    st.strategyId(),
                    (sid, cur) -> cur != null && cur.containerId().equals(st.containerId()) ? failed : cur);
            if (failed.consecutiveFailures() >= RESTART_THRESHOLD) {
                // 确认持续故障(连续达阈值)→ restart。withContainer 保 failures,MAX_FAILURES 给 restart
                // 后 1 个 healthCheck 恢复窗口:新容器 WS 重连秒级,30s 间隔内恢复则 onHealthy 重置 0;
                // 仍失败则累计至 MAX → markError(即 restart 没救=真问题)。
                restartStrategy(st.strategyId(), failed);
            }
            // 否则首次失败(< RESTART_THRESHOLD)→ 观察:仅更新 registry 不 restart。防秒级 WS 抖动/
            // 瞬时网络抖动立即 restart 丢策略内存状态(持仓上下文/累积指标)。下次 healthCheck(30s)若
            // 恢复 → onHealthy 重置 0;仍失败 → 累计达阈值才 restart。
        }
    }

    private void restartStrategy(long strategyId, WorkerStatus failed) {
        ReentrantLock lock = lockFor(strategyId);
        lock.lock();
        try {
            // 复查:registry 仍是本 containerId 且 DB status==RUNNING 才重启。
            // 防 stop 并发:stopWorker 已 registry.remove + CAS STOPPED,这里复查 registry null 或
            // containerId 不匹配 → 放弃(否则 createAndStart 拉新容器 + registry.put 复活 = 僵尸 worker,
            // DB STOPPED 但 worker 跑持 token 下单)。也防 markError 后重复重启。
            WorkerStatus current = registry.get(strategyId);
            if (current == null || !current.containerId().equals(failed.containerId())) {
                log.info(
                        "Restart aborted for strategy {}: registry changed (stop/markError won), failures={}",
                        strategyId,
                        failed.consecutiveFailures());
                return;
            }
            StrategyDefinition s = crudService.findById(strategyId);
            if (s.getStatus() != StrategyStatus.RUNNING) {
                log.info(
                        "Restart aborted for strategy {}: DB status {} (not RUNNING, stop won)",
                        strategyId,
                        s.getStatus());
                return;
            }
            stopContainerQuietly(failed.containerId());
            StrategyCode code = codeService.getPublishedCode(strategyId);
            if (code == null) {
                eventPublisher.publishEvent(new WorkerMarkErrorEvent(strategyId, "No published code for restart"));
                return;
            }
            String newContainerId = startContainer(s, code);
            registry.put(strategyId, failed.withContainer(newContainerId, Instant.now()));
        } catch (Exception e) {
            log.error("Restart failed for strategy {}", strategyId, e);
            // 留作 failed 状态，下次健康检查继续累计 → 最终 markError
        } finally {
            lock.unlock();
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

    /** 构建 WorkerConfig 并启动容器,返回 containerId(startWorker/restartStrategy 共用)。 */
    private String startContainer(StrategyDefinition strategy, StrategyCode code) {
        WorkerConfig config = buildConfig(strategy, code);
        // 先 put config 再 createAndStart:worker 容器启动后 GET /worker/bootstrap 拉 config 时必已就位
        // (createAndStart 返回后 docker 才启动容器,put 在其前 = 无竞态窗口)。
        configRegistry.put(strategy.getId(), config);
        return workerManager.createAndStart(config);
    }

    private WorkerConfig buildConfig(StrategyDefinition strategy, StrategyCode code) {
        // token 绑 strategyId+RUNNER+userId+exchange+accountId(start 验属 user 后绑);
        // WorkerTokenFilter 注入 WORKER_ACCOUNT_ID_ATTR,OrderController/PositionController 用 accountId(去 exchange 推导)。
        // WTS.issueToken 同 strategyId 重发自动 revoke 旧 token(reissue 语义,切账户时旧 token 失效)。
        Long accountId = strategy.getExchangeAccountId();
        if (accountId == null || accountId == 0) {
            // 防御:start 应先 set exchangeAccountId,reconcile 跳过 null;到这说明数据异常
            throw new IllegalStateException("strategy " + strategy.getId() + " has no exchange_account_id bound");
        }
        String token = workerTokenService.issueRunnerToken(
                strategy.getId(), strategy.getUserId(), strategy.getExchange(), accountId);
        return WorkerConfig.forStrategy(strategy, code, apiBaseUrl, token);
    }
}
