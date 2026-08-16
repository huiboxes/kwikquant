package com.kwikquant.strategy.application;

import com.kwikquant.strategy.domain.BacktestTask;
import com.kwikquant.strategy.domain.BacktestTaskStatus;
import com.kwikquant.strategy.infrastructure.BacktestTaskMapper;
import java.time.Duration;
import java.time.Instant;
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
    private final Duration staleGrace;

    public BacktestTaskRecovery(
            BacktestTaskMapper taskMapper,
            BacktestExecutionGateway executionGateway,
            @Value("${kwikquant.worker.timeout-sec:3600}") long timeoutSec,
            @Value("${kwikquant.backtest.stale-grace-sec:300}") long staleGraceSec) {
        this.taskMapper = taskMapper;
        this.executionGateway = executionGateway;
        this.staleGrace = Duration.ofSeconds(timeoutSec + staleGraceSec);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverOnStartup() {
        for (BacktestTask task : taskMapper.findActive()) {
            try {
                if (task.getStatus() == BacktestTaskStatus.PENDING) {
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
