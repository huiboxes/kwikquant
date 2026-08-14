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
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * DockerWorkerManager 单测:mock {@link SubprocessExecutor}(docker daemon 不可单测,同
 * DockerBacktestRunnerTest 模式)+ mock {@link WorkerHealthProbe}(HTTP 探活),验证容器安全旗标
 * (与 backtest 对齐)、配置 env 下发、超时/非零退出映射、stop/remove/list 分支、healthCheck
 * 委托 probe + isWorkerHealthy 纯函数判定。
 */
class DockerWorkerManagerTest {

    private final SubprocessExecutor executor = mock(SubprocessExecutor.class);
    private final WorkerHealthProbe healthProbe = mock(WorkerHealthProbe.class);
    // wsStaleMs=300000(5min)、maxOrderFailures=5(对齐 application 默认值),验证 isWorkerHealthy 判定
    private final DockerWorkerManager manager =
            new DockerWorkerManager(executor, healthProbe, "kwikquant-worker:latest", 300_000L, 5);

    /** isWorkerHealthy 纯函数测试用的固定 now(不依赖系统时钟,可复现);healthCheck 测试用真实时钟。 */
    private static final long NOW = 1_700_000_000_000L;

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

    /** 构造 /health 快照(lastBarAt/lastWsMsgAt 为 ms 时间戳;null=字段缺失)。 */
    private static WorkerHealthSnapshot snapshot(String status, Long lastBarAt, Long lastWsMsgAt, Integer failures) {
        return new WorkerHealthSnapshot(status, lastBarAt, lastWsMsgAt, failures);
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
    void createAndStart_envOnlyBootstrapGuidance_noSourceCodeInEnv() {
        // 拉取式 bootstrap(③):env 仅留引导参数(WORKER_SERVICE_TOKEN + KWIKQUANT_API_BASE),
        // sourceCode 不进 env(解 E2BIG + docker inspect 可窥),容器启动后 GET /worker/bootstrap 拉。
        when(executor.run(any(), any(), any(), anyLong())).thenReturn(SubprocessResult.of(0, "abc", "", false));
        manager.createAndStart(cfg());

        ArgumentCaptor<List<String>> cmdCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<String> stdinCaptor = ArgumentCaptor.forClass(String.class);
        verify(executor, atLeastOnce()).run(cmdCaptor.capture(), any(), stdinCaptor.capture(), anyLong());
        List<String> runCmd = cmdCaptor.getAllValues().stream()
                .filter(c -> c.contains("run") && !c.contains("rm"))
                .findFirst()
                .orElseThrow();
        assertThat(runCmd.toString()).doesNotContain("TASK_CONFIG_JSON"); // 不再 env 下发配置
        assertThat(runCmd.toString()).contains("WORKER_SERVICE_TOKEN=tok-abc"); // 引导 token 留 env
        assertThat(runCmd.toString()).contains("KWIKQUANT_API_BASE=http://kwikquant-app:8080");
        assertThat(runCmd.toString()).doesNotContain("on_bar"); // sourceCode 不再进 env(走 bootstrap 拉)
        // runner 后台(-d),stdin 不用于配置下发(detached 容器 stdin 不工作)
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

    // ===== healthCheck:HTTP 探活 via WorkerHealthProbe(mock probe,不走 docker inspect)=====

    @Test
    void healthCheck_trueWhenProbeReturnsHealthySnapshot() {
        long now = System.currentTimeMillis();
        when(healthProbe.probe("strategy-worker-42")).thenReturn(Optional.of(snapshot("ok", now, now, 0)));
        assertThat(manager.healthCheck("strategy-worker-42")).isTrue();
    }

    @Test
    void healthCheck_falseWhenProbeReturnsUnhealthySnapshot() {
        // WS stale(超过 5min 阈值)→ 不健康
        long now = System.currentTimeMillis();
        when(healthProbe.probe("strategy-worker-42")).thenReturn(Optional.of(snapshot("ok", now, now - 400_000L, 0)));
        assertThat(manager.healthCheck("strategy-worker-42")).isFalse();
    }

    @Test
    void healthCheck_falseWhenProbeReturnsEmpty() {
        // 探活失败(连不上/非 200/反序列化失败)→ empty → 不健康(容器死/网络断)
        when(healthProbe.probe("strategy-worker-42")).thenReturn(Optional.empty());
        assertThat(manager.healthCheck("strategy-worker-42")).isFalse();
    }

    // ===== isWorkerHealthy 纯函数判定(全分支,固定时钟 NOW)=====

    @Test
    void isWorkerHealthy_trueWhenStatusOkWsFreshFailuresUnderLimit() {
        assertThat(isHealthy(snapshot("ok", NOW, NOW, 0))).isTrue();
        assertThat(isHealthy(snapshot("ok", NOW, NOW, 4))).isTrue(); // 4 < 5
        assertThat(isHealthy(snapshot("ok", NOW, NOW - 299_999L, 0))).isTrue(); // ws 阈值边界内(300000-1)
    }

    @Test
    void isWorkerHealthy_falseWhenSnapshotNull() {
        assertThat(isHealthy(null)).isFalse();
    }

    @Test
    void isWorkerHealthy_falseWhenStatusNotOk() {
        assertThat(isHealthy(snapshot("degraded", NOW, NOW, 0))).isFalse();
        assertThat(isHealthy(snapshot(null, NOW, NOW, 0))).isFalse();
    }

    @Test
    void isWorkerHealthy_falseWhenLastWsMsgAtNull() {
        // WS 尚未连上(刚启动/WS 故障)→ 不健康
        assertThat(isHealthy(snapshot("ok", NOW, null, 0))).isFalse();
    }

    @Test
    void isWorkerHealthy_falseWhenWsStale() {
        // now - lastWsMsgAt > 300000(5min 阈值)→ 不健康
        assertThat(isHealthy(snapshot("ok", NOW, NOW - 300_001L, 0))).isFalse();
    }

    @Test
    void isWorkerHealthy_falseWhenOrderFailuresAtLimit() {
        // consecutiveOrderFailures >= 5 → 不健康
        assertThat(isHealthy(snapshot("ok", NOW, NOW, 5))).isFalse();
        assertThat(isHealthy(snapshot("ok", NOW, NOW, 99))).isFalse();
    }

    @Test
    void isWorkerHealthy_trueWhenOrderFailuresNull() {
        // consecutiveOrderFailures null(/health 不含此字段)→ 不判,其他 ok → 健康
        assertThat(isHealthy(snapshot("ok", NOW, NOW, null))).isTrue();
    }

    /** isWorkerHealthy 纯函数(用 manager 阈值 300000/5),验证 healthCheck 与纯函数判定一致。 */
    private boolean isHealthy(WorkerHealthSnapshot snap) {
        return DockerWorkerManager.isWorkerHealthy(snap, NOW, 300_000L, 5);
    }

    @Test
    void listStrategyWorkerContainers_parsesDockerPsNames() {
        when(executor.run(any(), any(), any(), anyLong()))
                .thenReturn(SubprocessResult.of(0, "strategy-worker-1\nstrategy-worker-2\n", "", false));
        assertThat(manager.listStrategyWorkerContainers()).containsExactly("strategy-worker-1", "strategy-worker-2");
    }

    @Test
    void listStrategyWorkerContainers_emptyWhenNoContainers() {
        when(executor.run(any(), any(), any(), anyLong())).thenReturn(SubprocessResult.of(0, "", "", false));
        assertThat(manager.listStrategyWorkerContainers()).isEmpty();
    }

    @Test
    void listStrategyWorkerContainers_emptyOnDockerPsFailure() {
        when(executor.run(any(), any(), any(), anyLong())).thenReturn(SubprocessResult.of(1, "err", "", false));
        assertThat(manager.listStrategyWorkerContainers()).isEmpty();
    }
}
