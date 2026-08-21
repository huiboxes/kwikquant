package com.kwikquant.strategy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link BacktestWorkerHealthChecker} 自检分支:解释器缺失/依赖缺失/全过/关闭/未配置。
 * SubprocessExecutor mock 掉真实 spawn。
 */
class BacktestWorkerHealthCheckerTest {

    private SubprocessExecutor executor;

    @BeforeEach
    void setUp() {
        executor = mock(SubprocessExecutor.class);
    }

    private static SubprocessResult ok() {
        return new SubprocessResult(0, "Python 3.12.3", "", false, false);
    }

    @Test
    void pythonMissing_configBlank_unavailable() {
        BacktestWorkerHealthChecker checker = new BacktestWorkerHealthChecker(executor, "", true);
        checker.selfCheck();
        assertThat(checker.isAvailable()).isFalse();
        assertThat(checker.detail()).contains("python-command");
    }

    @Test
    void interpreterNotExecutable_unavailable() {
        when(executor.run(anyList(), any(), any(), anyLong()))
                .thenReturn(new SubprocessResult(2, "", "No such file or directory", false, false));
        BacktestWorkerHealthChecker checker = new BacktestWorkerHealthChecker(executor, "/nonexistent/python", true);
        checker.selfCheck();
        assertThat(checker.isAvailable()).isFalse();
        assertThat(checker.detail()).contains("KWIKQUANT_WORKER_PYTHON");
    }

    @Test
    void importFails_unavailableWithPipHint() {
        when(executor.run(anyList(), any(), any(), anyLong()))
                .thenReturn(ok())
                .thenReturn(new SubprocessResult(
                        1, "", "ModuleNotFoundError: No module named 'kwikquant_worker'", false, false));
        BacktestWorkerHealthChecker checker = new BacktestWorkerHealthChecker(executor, ".venv/bin/python", true);
        checker.selfCheck();
        assertThat(checker.isAvailable()).isFalse();
        assertThat(checker.detail()).contains("requirements-worker.txt");
    }

    @Test
    void allChecksPass_available() {
        when(executor.run(anyList(), any(), any(), anyLong())).thenReturn(ok());
        BacktestWorkerHealthChecker checker = new BacktestWorkerHealthChecker(executor, ".venv/bin/python", true);
        checker.selfCheck();
        assertThat(checker.isAvailable()).isTrue();
    }

    @Test
    void selfCheckDisabled_assumesAvailable() {
        BacktestWorkerHealthChecker checker = new BacktestWorkerHealthChecker(executor, ".venv/bin/python", false);
        checker.selfCheck();
        assertThat(checker.isAvailable()).isTrue();
        assertThat(checker.detail()).contains("自检已关闭");
    }
}
