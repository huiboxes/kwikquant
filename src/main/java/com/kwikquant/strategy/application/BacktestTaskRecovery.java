package com.kwikquant.strategy.application;

import com.kwikquant.strategy.domain.BacktestTask;
import com.kwikquant.strategy.domain.BacktestTaskStatus;
import com.kwikquant.strategy.infrastructure.BacktestTaskMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 回测任务崩溃恢复与租约回收。
 *
 * <p><b>启动恢复</b>(ApplicationReadyEvent,仿 WorkerOrchestratorService.reconcileRunningStrategies 范式):
 * 应用重启后执行线程与 token/ledger 内存态全部蒸发,DB 里的活动任务无人认领——
 * PENDING → 重新 {@code executeAsync}(CAS PENDING→RUNNING 守卫防重复领取,安全);
 * RUNNING → markFailed(token 已失效、账本已丢失,半程状态不可续,用户重新提交)。
 *
 * <p><b>与 worker 自检的衔接</b>:自检未落定时(环境自动搭建窗口)直接重入队会让任务
 * 白跑一次失败,因此 PENDING 先暂缓,等 {@link WorkerEnvironmentSettledEvent} 再入队;
 * 落定后无论环境好坏都入队——坏环境下按正常失败路径分类报因,与门禁前置拒绝不冲突。
 *
 * <p><b>租约回收</b>(周期调度):RUNNING 且 updated_at 超过 timeout+宽限期 的任务判定失联 → markFailed。
 * 进度上报(每 200 bar)与结果写入均刷新 updated_at,天然充当 worker 心跳,无需新增心跳列。
 *
 * <p>单节点假设:恢复逻辑假定本实例是唯一执行者(与 WorkerTokenService 内存 registry 一致);
 * 多实例部署前必须先外置 token/租约(见部署文档红线)。
 */
@Component
public class BacktestTaskRecovery {

    private static final Logger log = LoggerFactory.getLogger(BacktestTaskRecovery.class);

    static final String RESTART_REASON = "服务重启，回测任务中断，请重新提交";
    static final String STALE_REASON = "回测执行超时失联，请重新提交";

    private final BacktestTaskMapper taskMapper;
    private final BacktestExecutionGateway executionGateway;
    private final Optional<BacktestWorkerHealthChecker> workerHealthChecker;
    private final Duration staleGrace;

    /** 自检未落定期间暂缓入队的 PENDING 任务;以 deferredPending 自身为锁。 */
    private final List<Long> deferredPending = new ArrayList<>();

    public BacktestTaskRecovery(
            BacktestTaskMapper taskMapper,
            BacktestExecutionGateway executionGateway,
            Optional<BacktestWorkerHealthChecker> workerHealthChecker,
            @Value("${kwikquant.worker.timeout-sec:3600}") long timeoutSec,
            @Value("${kwikquant.backtest.stale-grace-sec:300}") long staleGraceSec) {
        this.taskMapper = taskMapper;
        this.executionGateway = executionGateway;
        this.workerHealthChecker = workerHealthChecker;
        this.staleGrace = Duration.ofSeconds(timeoutSec + staleGraceSec);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverOnStartup() {
        for (BacktestTask task : taskMapper.findActive()) {
            try {
                if (task.getStatus() == BacktestTaskStatus.PENDING) {
                    if (workerHealthChecker.isPresent()
                            && !workerHealthChecker.get().settled()) {
                        synchronized (deferredPending) {
                            deferredPending.add(task.getId());
                        }
                        log.info(
                                "Recovery: defer pending backtest task {} until worker self-check settles",
                                task.getId());
                        continue;
                    }
                    log.info("Recovery: re-enqueue pending backtest task {}", task.getId());
                    executionGateway.executeAsync(task.getId());
                } else {
                    boolean failed =
                            executionGateway.markFailedByRecovery(task.getId(), task.getUserId(), RESTART_REASON);
                    if (failed) {
                        log.info("Recovery: mark stale running backtest task {} failed (restart)", task.getId());
                    }
                }
            } catch (Exception e) {
                // 单个任务恢复失败不阻断其余任务
                log.error("Recovery: failed to recover backtest task {}", task.getId(), e);
            }
        }
        // 自检可能在积压过程中已落定(事件先于积压清单填满触发),补一次排放防遗漏
        drainDeferred();
    }

    /** 自检落定后排放暂缓的 PENDING:环境可用则执行,不可用则走正常失败路径分类报因。 */
    @EventListener(WorkerEnvironmentSettledEvent.class)
    public void onWorkerEnvironmentSettled(WorkerEnvironmentSettledEvent event) {
        drainDeferred();
    }

    private void drainDeferred() {
        // 未落定不排放(启动收尾的补偿调用也走这里,防把暂缓任务立刻打出去)
        if (workerHealthChecker.isPresent() && !workerHealthChecker.get().settled()) {
            return;
        }
        List<Long> drained;
        synchronized (deferredPending) {
            if (deferredPending.isEmpty()) {
                return;
            }
            drained = List.copyOf(deferredPending);
            deferredPending.clear();
        }
        for (Long taskId : drained) {
            try {
                log.info("Recovery: re-enqueue deferred pending backtest task {}", taskId);
                executionGateway.executeAsync(taskId);
            } catch (Exception e) {
                log.error("Recovery: failed to re-enqueue deferred backtest task {}", taskId, e);
            }
        }
    }

    @Scheduled(fixedDelayString = "${kwikquant.backtest.stale-check-interval-ms:300000}")
    public void reclaimStaleRunning() {
        Instant before = Instant.now().minus(staleGrace);
        for (BacktestTask task : taskMapper.findStaleRunning(before)) {
            boolean failed = executionGateway.markFailedByRecovery(task.getId(), task.getUserId(), STALE_REASON);
            if (failed) {
                log.warn(
                        "Recovery: reclaimed stale running backtest task {} (no progress since {})",
                        task.getId(),
                        task.getUpdatedAt());
            }
        }
    }
}
