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

import com.kwikquant.strategy.application.BacktestResult;
import com.kwikquant.strategy.application.BacktestRunRequest;
import com.kwikquant.strategy.application.SubprocessExecutor;
import com.kwikquant.strategy.application.SubprocessResult;
import com.kwikquant.strategy.domain.BacktestNoMarketDataException;
import com.kwikquant.strategy.domain.BacktestRunnerException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

/**
 * DockerBacktestRunner 单测:mock {@link SubprocessExecutor}(docker CLI 不可单测,同
 * PythonSubprocessBacktestRunnerTest 模式),验证容器安全旗标、stdin 配置下发、退出码映射与清理。
 */
class DockerBacktestRunnerTest {

    private final SubprocessExecutor executor = mock(SubprocessExecutor.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DockerBacktestRunner runner = new DockerBacktestRunner(
            executor, objectMapper, "kwikquant-worker:latest", "http://kwikquant-app:8080", "2g", "1", 60);

    private static final String SECTION8 =
            "{\"trades\":[{\"time\":\"2024-01-15T08:00:00Z\",\"side\":\"buy\",\"price\":\"42150\",\"amount\":\"0.1\",\"fee\":\"4.215\"}],"
                    + "\"equity_curve\":[{\"time\":\"2024-01-01\",\"equity\":\"10000\"},{\"time\":\"2024-01-02\",\"equity\":\"10023.5\"}],"
                    + "\"metrics\":{}}";

    private static BacktestRunRequest req() {
        return new BacktestRunRequest(
                77,
                1,
                1,
                1,
                "BTC/USDT",
                "BINANCE",
                "1h",
                Instant.parse("2024-01-01T00:00:00Z"),
                Instant.parse("2025-01-01T00:00:00Z"),
                "{}",
                "token-abc",
                "SPOT",
                "def on_bar(bar, ctx):\n    pass",
                Map.of("marketSlippageBps", "5"));
    }

    @Test
    void run_happy_returnsResultWithSection8AndSummary() {
        when(executor.run(any(), any(), any(), anyLong())).thenReturn(SubprocessResult.of(0, SECTION8, "", false));
        BacktestResult result = runner.run(req());
        assertThat(result.tradeCount()).isEqualTo(1);
        assertThat(result.totalPnl()).isEqualByComparingTo("23.5");
        assertThat(result.section8Json()).isEqualTo(SECTION8);
    }

    @SuppressWarnings("unchecked")
    @Test
    void run_buildsDockerCommandWithIsolationFlags() {
        when(executor.run(any(), any(), any(), anyLong())).thenReturn(SubprocessResult.of(0, SECTION8, "", false));
        runner.run(req());

        ArgumentCaptor<List<String>> cmdCaptor = ArgumentCaptor.forClass(List.class);
        verify(executor, atLeastOnce()).run(cmdCaptor.capture(), any(), any(), anyLong());
        // 取 docker run 那次调用(其余是 rm -f 清理)
        List<String> cmd = cmdCaptor.getAllValues().stream()
                .filter(c -> c.contains("run"))
                .findFirst()
                .orElseThrow();

        // 不可信代码执行加固旗标全集(与 DockerWorkerManager 对齐并加强)
        assertThat(cmd).containsSequence("--user", "1000:1000");
        assertThat(cmd).contains("--read-only");
        assertThat(cmd).contains("--security-opt=no-new-privileges");
        assertThat(cmd).contains("--cap-drop=ALL");
        assertThat(cmd).contains("--pids-limit=256");
        assertThat(cmd).contains("--memory=2g");
        assertThat(cmd).contains("--memory-swap=2g");
        assertThat(cmd).contains("--cpus=1");
        assertThat(cmd).containsSequence("--network", "kwikquant-worker-net");
        assertThat(cmd).contains("--no-healthcheck");
        assertThat(cmd).contains("--init");
        assertThat(cmd).contains("--rm");
        assertThat(cmd).contains("-i"); // stdin 配置通道
        assertThat(cmd).containsSequence("--name", "backtest-worker-77");
        assertThat(cmd).containsSequence("--env", "KWIKQUANT_API_BASE=http://kwikquant-app:8080");
        assertThat(cmd.get(cmd.size() - 1)).isEqualTo("--mode=backtest");
        assertThat(cmd).contains("kwikquant-worker:latest");
    }

    @SuppressWarnings("unchecked")
    @Test
    void run_passesConfigViaStdin_notEnv() {
        // 配置(含策略源码 + serviceToken)走 stdin:避开 env ~128KB 上限与 docker inspect 可窥
        when(executor.run(any(), any(), any(), anyLong())).thenReturn(SubprocessResult.of(0, SECTION8, "", false));
        runner.run(req());

        ArgumentCaptor<String> stdinCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map<String, String>> envCaptor = ArgumentCaptor.forClass(Map.class);
        verify(executor, atLeastOnce()).run(any(), envCaptor.capture(), stdinCaptor.capture(), anyLong());
        // 找到 docker run 那次(stdin 非 null;rm -f 清理 stdin=null)
        String stdin = stdinCaptor.getAllValues().stream()
                .filter(s -> s != null)
                .findFirst()
                .orElseThrow();
        assertThat(stdin).contains("\"taskId\":77");
        assertThat(stdin).contains("token-abc");
        assertThat(stdin).contains("on_bar");

        Map<String, String> runEnv = envCaptor.getAllValues().stream()
                .filter(e -> e.containsKey("KWIKQUANT_API_BASE"))
                .findFirst()
                .orElseThrow();
        assertThat(runEnv).containsOnlyKeys("KWIKQUANT_API_BASE");
        assertThat(runEnv).doesNotContainKeys("TASK_CONFIG_JSON", "WORKER_SERVICE_TOKEN");
    }

    @Test
    void run_cleansUpContainerBeforeAndAfter() {
        // 前置幂等清理(daemon 重启残留)+ finally 清理(超时路径 docker CLI 被杀但容器可能存活)
        when(executor.run(any(), any(), any(), anyLong())).thenReturn(SubprocessResult.of(0, SECTION8, "", false));
        runner.run(req());
        verify(executor, atLeastOnce())
                .run(eq(List.of("docker", "rm", "-f", "backtest-worker-77")), any(), any(), anyLong());
    }

    @Test
    void run_timeout_throwsAndCleansUp() {
        when(executor.run(any(), any(), any(), anyLong())).thenReturn(SubprocessResult.of(-1, "", "", true));
        assertThatThrownBy(() -> runner.run(req())).isInstanceOf(BacktestRunnerException.class);
        // 超时后必须强制回收容器(destroyForcibly 只杀 docker CLI 客户端)
        verify(executor, atLeastOnce())
                .run(eq(List.of("docker", "rm", "-f", "backtest-worker-77")), any(), any(), anyLong());
    }

    @Test
    void run_exit2_throwsBacktestNoMarketDataException() {
        when(executor.run(any(), any(), any(), anyLong()))
                .thenReturn(new SubprocessResult(2, "", "NO_MARKET_DATA: OKX SPOT BTC/USDT 1h 无历史数据", false, false));
        assertThatThrownBy(() -> runner.run(req()))
                .isInstanceOf(BacktestNoMarketDataException.class)
                .hasMessageContaining("无历史数据");
    }

    @Test
    void run_nonZeroExit_throwsBacktestRunnerException() {
        when(executor.run(any(), any(), any(), anyLong()))
                .thenReturn(new SubprocessResult(1, "", "boom", false, false));
        assertThatThrownBy(() -> runner.run(req())).isInstanceOf(BacktestRunnerException.class);
    }

    @Test
    void run_emptyStdout_throwsBacktestRunnerException() {
        when(executor.run(any(), any(), any(), anyLong())).thenReturn(SubprocessResult.of(0, "  ", "", false));
        assertThatThrownBy(() -> runner.run(req())).isInstanceOf(BacktestRunnerException.class);
    }

    @Test
    void run_stdoutTruncated_throwsBacktestRunnerException() {
        when(executor.run(any(), any(), any(), anyLong()))
                .thenReturn(new SubprocessResult(0, "{\"trades\":[", "", false, true));
        assertThatThrownBy(() -> runner.run(req()))
                .isInstanceOf(BacktestRunnerException.class)
                .hasMessageContaining("截断");
    }

    @Test
    void run_unconfigured_throwsBacktestRunnerException() {
        DockerBacktestRunner unconfigured = new DockerBacktestRunner(executor, objectMapper, "", "", "2g", "1", 60);
        assertThatThrownBy(() -> unconfigured.run(req()))
                .isInstanceOf(BacktestRunnerException.class)
                .hasMessageContaining("未配置");
    }
}
