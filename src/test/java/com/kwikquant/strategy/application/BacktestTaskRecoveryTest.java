package com.kwikquant.strategy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kwikquant.strategy.domain.BacktestTask;
import com.kwikquant.strategy.domain.BacktestTaskStatus;
import com.kwikquant.strategy.infrastructure.BacktestTaskMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * BacktestTaskRecovery 单测:启动恢复(PENDING 重入队 / RUNNING 标失败 / 自检未落定时暂缓)+ 租约回收。
 */
class BacktestTaskRecoveryTest {

    private BacktestTaskMapper taskMapper;
    private BacktestExecutionGateway gateway;
    private BacktestTaskRecovery recovery;

    @BeforeEach
    void setUp() {
        taskMapper = mock(BacktestTaskMapper.class);
        gateway = mock(BacktestExecutionGateway.class);
        recovery = new BacktestTaskRecovery(taskMapper, gateway, Optional.empty(), 3600, 300);
    }

    private static BacktestTask task(long id, long userId, BacktestTaskStatus status) {
        BacktestTask t = new BacktestTask();
        t.setId(id);
        t.setUserId(userId);
        t.setStatus(status);
        return t;
    }

    @Test
    void recoverOnStartup_pendingReEnqueued_runningMarkedFailed() {
        when(taskMapper.findActive())
                .thenReturn(List.of(task(1, 10, BacktestTaskStatus.PENDING), task(2, 20, BacktestTaskStatus.RUNNING)));
        when(gateway.markFailedByRecovery(eq(2L), eq(20L), eq(BacktestTaskRecovery.RESTART_REASON)))
                .thenReturn(true);

        recovery.recoverOnStartup();

        verify(gateway).executeAsync(1L);
        verify(gateway).markFailedByRecovery(eq(2L), eq(20L), eq(BacktestTaskRecovery.RESTART_REASON));
    }

    @Test
    void recoverOnStartup_noActiveTasks_noop() {
        when(taskMapper.findActive()).thenReturn(List.of());
        recovery.recoverOnStartup();
        verify(gateway, never()).executeAsync(anyLong());
        verify(gateway, never()).markFailedByRecovery(anyLong(), anyLong(), anyString());
    }

    @Test
    void recoverOnStartup_singleTaskFailureDoesNotBlockRest() {
        when(taskMapper.findActive())
                .thenReturn(List.of(
                        task(1, 10, BacktestTaskStatus.PENDING),
                        task(2, 20, BacktestTaskStatus.PENDING),
                        task(3, 30, BacktestTaskStatus.RUNNING)));
        org.mockito.Mockito.doThrow(new RuntimeException("boom")).when(gateway).executeAsync(1L);

        recovery.recoverOnStartup();

        verify(gateway).executeAsync(2L); // 第一个失败不阻断后续
        verify(gateway).markFailedByRecovery(eq(3L), eq(30L), anyString());
    }

    @Test
    void recoverOnStartup_checkerUnsettled_defersPendingUntilSettled() {
        // 环境自动搭建窗口(自检未落定)内重入队会让任务白跑失败 → 暂缓到落定事件再入队
        BacktestWorkerHealthChecker checker = mock(BacktestWorkerHealthChecker.class);
        when(checker.settled()).thenReturn(false);
        BacktestTaskRecovery guarded = new BacktestTaskRecovery(taskMapper, gateway, Optional.of(checker), 3600, 300);
        when(taskMapper.findActive()).thenReturn(List.of(task(1, 10, BacktestTaskStatus.PENDING)));

        guarded.recoverOnStartup();

        verify(gateway, never()).executeAsync(anyLong());

        // 自检落定(真实时序:先发事件时 settled 已为 true)
        when(checker.settled()).thenReturn(true);
        guarded.onWorkerEnvironmentSettled(new WorkerEnvironmentSettledEvent(true));
        verify(gateway).executeAsync(1L);
        // 重复事件不重复入队
        guarded.onWorkerEnvironmentSettled(new WorkerEnvironmentSettledEvent(false));
        verify(gateway, org.mockito.Mockito.times(1)).executeAsync(anyLong());
    }

    @Test
    void recoverOnStartup_checkerSettled_enqueuesDirectly() {
        BacktestWorkerHealthChecker checker = mock(BacktestWorkerHealthChecker.class);
        when(checker.settled()).thenReturn(true);
        BacktestTaskRecovery guarded = new BacktestTaskRecovery(taskMapper, gateway, Optional.of(checker), 3600, 300);
        when(taskMapper.findActive()).thenReturn(List.of(task(1, 10, BacktestTaskStatus.PENDING)));

        guarded.recoverOnStartup();

        verify(gateway).executeAsync(1L);
    }

    @Test
    void reclaimStaleRunning_usesTimeoutPlusGraceAsCutoff() {
        when(taskMapper.findStaleRunning(any())).thenReturn(List.of());

        recovery.reclaimStaleRunning();

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(taskMapper).findStaleRunning(cutoff.capture());
        // cutoff ≈ now - (3600 + 300)s
        Instant expected = Instant.now().minus(Duration.ofSeconds(3900));
        assertThat(cutoff.getValue()).isCloseTo(expected, within(10, java.time.temporal.ChronoUnit.SECONDS));
    }

    @Test
    void reclaimStaleRunning_marksStaleTasksFailed() {
        when(taskMapper.findStaleRunning(any())).thenReturn(List.of(task(9, 90, BacktestTaskStatus.RUNNING)));
        when(gateway.markFailedByRecovery(eq(9L), eq(90L), anyString())).thenReturn(true);

        recovery.reclaimStaleRunning();

        verify(gateway).markFailedByRecovery(eq(9L), eq(90L), eq(BacktestTaskRecovery.STALE_REASON));
    }
}
