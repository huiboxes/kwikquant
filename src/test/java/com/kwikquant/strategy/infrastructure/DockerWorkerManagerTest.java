package com.kwikquant.strategy.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kwikquant.strategy.application.SubprocessExecutor;
import com.kwikquant.strategy.application.SubprocessResult;
import com.kwikquant.strategy.application.WorkerConfig;
import com.kwikquant.strategy.domain.WorkerStartFailedException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * DockerWorkerManager 单测:mock {@link SubprocessExecutor}(docker daemon 不可单测,同
 * DockerBacktestRunnerTest 模式),验证容器安全旗标(与 backtest 对齐)、配置 env 下发、
 * 超时/非零退出映射、stop/remove/healthCheck 分支。
 */
class DockerWorkerManagerTest {

    private final SubprocessExecutor executor = mock(SubprocessExecutor.class);
    private final DockerWorkerManager manager = new DockerWorkerManager(executor, "kwikquant-worker:latest");

    private static WorkerConfig cfg() {
        return new WorkerConfig(
                42L,
                "my-strat",
                "def on_bar(bar, ctx):\n    pass",
                "BTC/USDT",
                "OKX",
                "SPOT",
                "1h",
                "{}",
                "http://kwikquant-app:8080",
                "tok-abc",
                512,
                1,
                3600);
    }

    @Test
    void createAndStart_returnsContainerName_onSuccess() {
        when(executor.run(any(), any(), any(), anyLong())).thenReturn(SubprocessResult.of(0, "abc123", "", false));
        assertThat(manager.createAndStart(cfg())).isEqualTo("strategy-worker-42");
    }

    @SuppressWarnings("unchecked")
    @Test
    void createAndStart_buildsCommandWithIsolationFlags() {
        when(executor.run(any(), any(), any(), anyLong())).thenReturn(SubprocessResult.of(0, "abc", "", false));
        manager.createAndStart(cfg());

        ArgumentCaptor<List<String>> cmdCaptor = ArgumentCaptor.forClass(List.class);
        verify(executor, atLeastOnce()).run(cmdCaptor.capture(), any(), any(), anyLong());
        // 取 docker run 那次(其余是 rm -f 清理)
        List<String> runCmd = cmdCaptor.getAllValues().stream()
                .filter(c -> c.contains("run") && !c.contains("rm"))
                .findFirst()
                .orElseThrow();

        // 不可信代码执行加固旗标全集(与 DockerBacktestRunner 对齐——runner 同样 exec 用户 on_bar)
        assertThat(runCmd).contains("-d"); // runner 长驻后台(backtest 是前台 -i)
        assertThat(runCmd).contains("--init");
        assertThat(runCmd).contains("--rm");
        assertThat(runCmd).containsSequence("--name", "strategy-worker-42");
        assertThat(runCmd).contains("--user", "1000:1000");
        assertThat(runCmd).contains("--read-only");
        assertThat(runCmd).contains("--security-opt=no-new-privileges");
        assertThat(runCmd).contains("--cap-drop=ALL");
        assertThat(runCmd).contains("--pids-limit=256");
        assertThat(runCmd).containsSequence("--tmpfs", "/tmp:rw,noexec,nosuid,size=64m");
        assertThat(runCmd).contains("--memory=512m");
        assertThat(runCmd).contains("--memory-swap=512m"); // 禁 swap
        assertThat(runCmd).contains("--cpus=1");
        assertThat(runCmd).containsSequence("--network", "kwikquant-worker-net");
        assertThat(runCmd.get(runCmd.size() - 1)).isEqualTo("--mode=runner"); // 显式,不靠 CMD 默认
        assertThat(runCmd).contains("kwikquant-worker:latest");
    }

    @SuppressWarnings("unchecked")
    @Test
    void createAndStart_passesConfigViaEnvAndNullStdin() {
        // runner 配置(含 sourceCode + serviceToken)走 --env TASK_CONFIG_JSON,非 stdin(后续批改 bootstrap/stdin)
        when(executor.run(any(), any(), any(), anyLong())).thenReturn(SubprocessResult.of(0, "abc", "", false));
        manager.createAndStart(cfg());

        ArgumentCaptor<List<String>> cmdCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<String> stdinCaptor = ArgumentCaptor.forClass(String.class);
        verify(executor, atLeastOnce()).run(cmdCaptor.capture(), any(), stdinCaptor.capture(), anyLong());
        List<String> runCmd = cmdCaptor.getAllValues().stream()
                .filter(c -> c.contains("run") && !c.contains("rm"))
                .findFirst()
                .orElseThrow();
        assertThat(runCmd.toString()).contains("TASK_CONFIG_JSON=");
        assertThat(runCmd.toString()).contains("WORKER_SERVICE_TOKEN=tok-abc");
        assertThat(runCmd.toString()).contains("on_bar"); // sourceCode 在 TASK_CONFIG_JSON 里
        // runner 后台(-d),stdin 不用于配置下发
        assertThat(stdinCaptor.getValue()).isNull();
    }

    @SuppressWarnings("unchecked")
    @Test
    void createAndStart_cleansUpOrphanBeforeRun() {
        // 前置 docker rm -f 同名容器幂等清理(运行中 SIGKILL),不依赖内存 registry
        when(executor.run(any(), any(), any(), anyLong())).thenReturn(SubprocessResult.of(0, "abc", "", false));
        manager.createAndStart(cfg());
        verify(executor, atLeastOnce())
                .run(eq(List.of("docker", "rm", "-f", "strategy-worker-42")), any(), any(), anyLong());
    }

    @Test
    void createAndStart_timeout_throwsStartFailed() {
        when(executor.run(any(), any(), any(), anyLong())).thenReturn(SubprocessResult.of(-1, "", "", true));
        assertThatThrownBy(() -> manager.createAndStart(cfg()))
                .isInstanceOf(WorkerStartFailedException.class)
                .hasMessageContaining("timed out");
    }

    @Test
    void createAndStart_nonZeroExit_throwsStartFailed() {
        when(executor.run(any(), any(), any(), anyLong()))
                .thenReturn(SubprocessResult.of(1, "daemon error", "", false));
        assertThatThrownBy(() -> manager.createAndStart(cfg()))
                .isInstanceOf(WorkerStartFailedException.class)
                .hasMessageContaining("daemon error");
    }

    @Test
    void stop_callsDockerStop() {
        when(executor.run(any(), any(), any(), anyLong())).thenReturn(SubprocessResult.of(0, "", "", false));
        manager.stop("strategy-worker-42");
        verify(executor).run(eq(List.of("docker", "stop", "strategy-worker-42")), any(), any(), anyLong());
    }

    @Test
    void remove_callsDockerRmForce() {
        when(executor.run(any(), any(), any(), anyLong())).thenReturn(SubprocessResult.of(0, "", "", false));
        manager.remove("strategy-worker-42");
        verify(executor).run(eq(List.of("docker", "rm", "-f", "strategy-worker-42")), any(), any(), anyLong());
    }

    @Test
    void healthCheck_trueWhenInspectReturnsTrue() {
        when(executor.run(any(), any(), any(), anyLong())).thenReturn(SubprocessResult.of(0, "true", "", false));
        assertThat(manager.healthCheck("strategy-worker-42")).isTrue();
    }

    @Test
    void healthCheck_falseWhenInspectReturnsFalse() {
        when(executor.run(any(), any(), any(), anyLong())).thenReturn(SubprocessResult.of(0, "false", "", false));
        assertThat(manager.healthCheck("strategy-worker-42")).isFalse();
    }

    @Test
    void healthCheck_falseWhenInspectFails() {
        when(executor.run(any(), any(), any(), anyLong())).thenReturn(SubprocessResult.of(-1, "", "err", false));
        assertThat(manager.healthCheck("strategy-worker-42")).isFalse();
    }
}
