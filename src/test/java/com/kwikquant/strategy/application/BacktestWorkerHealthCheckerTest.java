package com.kwikquant.strategy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kwikquant.strategy.application.WorkerEnvironmentProvisioner.ProvisionResult;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

/**
 * {@link BacktestWorkerHealthChecker} 自检分支：解释器缺失/依赖缺失/全过/关闭/未配置、
 * 门禁初始态（开启自检时 fail-closed），以及自动搭建路径（建 venv + 装依赖 → 复验通过/
 * 搭建失败/复验仍败）。SubprocessExecutor 与 {@link WorkerEnvironmentProvisioner} 均 mock，
 * 直接调 selfCheck() 同步执行。
 */
class BacktestWorkerHealthCheckerTest {

    private SubprocessExecutor executor;
    private WorkerEnvironmentProvisioner provisioner;
    private ApplicationEventPublisher eventPublisher;

    @BeforeEach
    void setUp() {
        executor = mock(SubprocessExecutor.class);
        provisioner = mock(WorkerEnvironmentProvisioner.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
    }

    private BacktestWorkerHealthChecker checker(String pythonCommand, boolean enabled, boolean autoSetup) {
        return new BacktestWorkerHealthChecker(
                executor, provisioner, eventPublisher, pythonCommand, enabled, autoSetup);
    }

    private static SubprocessResult ok() {
        return new SubprocessResult(0, "Python 3.12.3", "", false, false);
    }

    private static SubprocessResult spawnFailed() {
        return new SubprocessResult(
                -1, "", "spawn failed: Cannot run program: No such file or directory", false, false);
    }

    @Test
    void selfCheckEnabled_unavailableUntilChecked() {
        // 门禁 fail-closed：@Async 自检完成前不放行
        BacktestWorkerHealthChecker checker = checker(".venv/bin/python", true, true);
        assertThat(checker.isAvailable()).isFalse();
        assertThat(checker.settled()).isFalse();
        assertThat(checker.detail()).contains("自检进行中");
    }

    @Test
    void selfCheckDisabled_availableFromConstruction() {
        BacktestWorkerHealthChecker checker = checker(".venv/bin/python", false, true);
        assertThat(checker.isAvailable()).isTrue();
        assertThat(checker.settled()).isTrue();
        assertThat(checker.detail()).contains("自检已关闭");
        checker.selfCheck();
        assertThat(checker.isAvailable()).isTrue();
    }

    @Test
    void pythonMissing_configBlank_unavailable() {
        BacktestWorkerHealthChecker checker = checker("", true, true);
        checker.selfCheck();
        assertThat(checker.isAvailable()).isFalse();
        assertThat(checker.detail()).contains("python-command");
    }

    @Test
    void interpreterNotExecutable_autoSetupDisabled_manualHintWithScriptAndEnvVar() {
        when(executor.run(anyList(), any(), any(), anyLong())).thenReturn(spawnFailed());
        BacktestWorkerHealthChecker checker = checker("/nonexistent/python", true, false);
        checker.selfCheck();
        assertThat(checker.isAvailable()).isFalse();
        assertThat(checker.detail()).contains("KWIKQUANT_WORKER_PYTHON");
        assertThat(checker.detail()).contains("scripts/setup-worker-env.sh");
        assertThat(checker.detail()).contains("重启后端");
        // 用户可见文案不透出 spawn 原文
        assertThat(checker.detail()).doesNotContain("spawn failed");
        assertThat(checker.detail()).contains("命令不存在或不可执行");
        verify(provisioner, never()).provision(anyString(), anyBoolean());
    }

    @Test
    void interpreterNotExecutable_bareCommand_neverProvisions() {
        // 裸命令名 = PATH 缺失，自动搭建帮不上；引导安装 python3
        when(executor.run(anyList(), any(), any(), anyLong())).thenReturn(spawnFailed());
        BacktestWorkerHealthChecker checker = checker("python3", true, true);
        checker.selfCheck();
        assertThat(checker.isAvailable()).isFalse();
        assertThat(checker.detail()).contains("安装 python3");
        assertThat(checker.detail()).contains("KWIKQUANT_WORKER_PYTHON");
        verify(provisioner, never()).provision(anyString(), anyBoolean());
    }

    @Test
    void importFails_autoSetupDisabled_manualHintWithPipInstall() {
        when(executor.run(anyList(), any(), any(), anyLong()))
                .thenReturn(ok())
                .thenReturn(new SubprocessResult(1, "", "ModuleNotFoundError: No module named 'httpx'", false, false));
        BacktestWorkerHealthChecker checker = checker(".venv/bin/python", true, false);
        checker.selfCheck();
        assertThat(checker.isAvailable()).isFalse();
        assertThat(checker.detail()).contains("requirements-worker.txt");
        assertThat(checker.detail()).contains("scripts/setup-worker-env.sh");
        verify(provisioner, never()).provision(anyString(), anyBoolean());
    }

    @Test
    void allChecksPass_available_noProvisioning() {
        when(executor.run(anyList(), any(), any(), anyLong())).thenReturn(ok());
        BacktestWorkerHealthChecker checker = checker(".venv/bin/python", true, true);
        checker.selfCheck();
        assertThat(checker.isAvailable()).isTrue();
        assertThat(checker.settled()).isTrue();
        assertThat(checker.detail()).contains("ok");
        // 落定事件:启动恢复靠它排放暂缓的 PENDING 回测
        verify(eventPublisher).publishEvent(new WorkerEnvironmentSettledEvent(true));
        verify(provisioner, never()).provision(anyString(), anyBoolean());
    }

    @Test
    void probe_importsRuntimeDependencySurface() {
        // 探测必须验到三方依赖面（只验 kwikquant_worker 探不出损坏/陈旧的 venv）
        when(executor.run(anyList(), any(), any(), anyLong())).thenReturn(ok());
        BacktestWorkerHealthChecker checker = checker(".venv/bin/python", true, true);
        checker.selfCheck();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> commands = ArgumentCaptor.forClass(List.class);
        verify(executor, org.mockito.Mockito.times(2)).run(commands.capture(), any(), any(), anyLong());
        assertThat(commands.getAllValues().get(0)).containsExactly(".venv/bin/python", "--version");
        assertThat(commands.getAllValues().get(1))
                .containsExactly(".venv/bin/python", "-c", BacktestWorkerHealthChecker.PROBE_IMPORTS);
        assertThat(BacktestWorkerHealthChecker.PROBE_IMPORTS).contains("httpx");
    }

    @Test
    void interpreterMissing_autoSetup_createsVenvAndRechecks() {
        // 首探解释器缺失 → provision(createVenv=true) → 复探两步全过
        when(executor.run(anyList(), any(), any(), anyLong()))
                .thenReturn(spawnFailed())
                .thenReturn(ok())
                .thenReturn(ok());
        when(provisioner.provision(".venv/bin/python", true))
                .thenReturn(ProvisionResult.ok("已创建虚拟环境 .venv，已按 requirements-worker.txt 安装 worker 依赖"));
        BacktestWorkerHealthChecker checker = checker(".venv/bin/python", true, true);
        checker.selfCheck();
        verify(provisioner).provision(".venv/bin/python", true);
        assertThat(checker.isAvailable()).isTrue();
        assertThat(checker.detail()).contains("自动搭建");
    }

    @Test
    void autoSetupInProgress_detailSaysPreparing() {
        // 搭建窗口内门禁拒绝的文案要能让用户知道"等一会重试"而不是以为坏了；
        // 在 provision 执行瞬间断言窗口内的状态与文案
        when(executor.run(anyList(), any(), any(), anyLong())).thenReturn(spawnFailed());
        BacktestWorkerHealthChecker checker = checker(".venv/bin/python", true, true);
        when(provisioner.provision(".venv/bin/python", true)).thenAnswer(invocation -> {
            assertThat(checker.isAvailable()).isFalse();
            assertThat(checker.detail()).contains(BacktestWorkerHealthChecker.AUTO_SETUP_MARKER);
            assertThat(checker.detail()).contains("请稍候重试");
            return ProvisionResult.failed("boom");
        });
        checker.selfCheck();
        assertThat(checker.isAvailable()).isFalse();
    }

    @Test
    void importFails_autoSetup_onlyPipInstalls() {
        // 解释器在但依赖不全 → provision(createVenv=false) → 复探全过
        when(executor.run(anyList(), any(), any(), anyLong()))
                .thenReturn(ok())
                .thenReturn(spawnFailed())
                .thenReturn(ok())
                .thenReturn(ok());
        when(provisioner.provision(".venv/bin/python", false))
                .thenReturn(ProvisionResult.ok("已按 requirements-worker.txt 安装 worker 依赖"));
        BacktestWorkerHealthChecker checker = checker(".venv/bin/python", true, true);
        checker.selfCheck();
        verify(provisioner).provision(".venv/bin/python", false);
        assertThat(checker.isAvailable()).isTrue();
    }

    @Test
    void autoSetupFailed_unavailableWithManualFallback() {
        when(executor.run(anyList(), any(), any(), anyLong())).thenReturn(spawnFailed());
        when(provisioner.provision(".venv/bin/python", true))
                .thenReturn(ProvisionResult.failed("创建虚拟环境失败（exit 1: ensurepip missing）"));
        BacktestWorkerHealthChecker checker = checker(".venv/bin/python", true, true);
        checker.selfCheck();
        assertThat(checker.isAvailable()).isFalse();
        assertThat(checker.detail()).contains("自动搭建失败");
        assertThat(checker.detail()).contains("scripts/setup-worker-env.sh");
        assertThat(checker.detail()).contains("重启后端");
    }

    @Test
    void autoSetupOkButRecheckStillFails_unavailable() {
        // 搭建声称成功但复探仍败（如装到了错误位置）→ 不能误报可用
        when(executor.run(anyList(), any(), any(), anyLong())).thenReturn(spawnFailed());
        when(provisioner.provision(".venv/bin/python", true)).thenReturn(ProvisionResult.ok("ok"));
        BacktestWorkerHealthChecker checker = checker(".venv/bin/python", true, true);
        checker.selfCheck();
        assertThat(checker.isAvailable()).isFalse();
        assertThat(checker.detail()).contains("复验仍未通过");
    }

    @Test
    void unexpectedException_unavailableNotStuck() {
        // 自检内任何未预期异常都不能让门禁停在未知态
        when(executor.run(anyList(), any(), any(), anyLong())).thenThrow(new IllegalStateException("boom"));
        BacktestWorkerHealthChecker checker = checker(".venv/bin/python", true, true);
        checker.selfCheck();
        assertThat(checker.isAvailable()).isFalse();
        assertThat(checker.detail()).contains("自检异常");
    }
}
