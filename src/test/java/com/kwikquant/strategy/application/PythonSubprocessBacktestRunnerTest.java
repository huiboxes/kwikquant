package com.kwikquant.strategy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kwikquant.strategy.domain.BacktestNoMarketDataException;
import com.kwikquant.strategy.domain.BacktestRunnerException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

class PythonSubprocessBacktestRunnerTest {

    private final SubprocessExecutor executor = mock(SubprocessExecutor.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PythonSubprocessBacktestRunner runner = new PythonSubprocessBacktestRunner(
            executor,
            objectMapper,
            "python",
            "worker_server.py",
            "host=localhost dbname=kwikquant",
            "http://localhost:8080",
            60);

    private static final String SECTION8 =
            "{\"trades\":[{\"time\":\"2024-01-15T08:00:00Z\",\"side\":\"buy\",\"price\":\"42150\",\"amount\":\"0.1\",\"fee\":\"4.215\"}],"
                    + "\"equity_curve\":[{\"time\":\"2024-01-01\",\"equity\":\"10000\"},{\"time\":\"2024-01-02\",\"equity\":\"10023.5\"}],"
                    + "\"metrics\":{}}";

    private static BacktestRunRequest req() {
        return new BacktestRunRequest(
                1,
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
                "pass");
    }

    @Test
    void run_exit2_throwsBacktestNoMarketDataException() {
        // Task 7: worker 拉空 exit 2 + stderr NO_MARKET_DATA → 抛 BacktestNoMarketDataException
        // (Gateway catch markFailed 7304),非 BacktestRunnerException(7300)
        when(executor.run(any(), any(), anyLong()))
                .thenReturn(new SubprocessResult(
                        2, "", "NO_MARKET_DATA: OKX SPOT BTC/USDT 1h 2024-01-01~2024-01-02 无历史数据", false));

        assertThatThrownBy(() -> runner.run(req()))
                .isInstanceOf(BacktestNoMarketDataException.class)
                .hasMessageContaining("OKX SPOT BTC/USDT")
                .hasMessageContaining("无历史数据");
    }

    @Test
    void run_exit2_withoutMarker_usesStderrAsMessage() {
        // stderr 无 NO_MARKET_DATA: 标记(兜底)→ 用 stderr 全文
        when(executor.run(any(), any(), anyLong())).thenReturn(new SubprocessResult(2, "", "some worker error", false));

        assertThatThrownBy(() -> runner.run(req()))
                .isInstanceOf(BacktestNoMarketDataException.class)
                .hasMessageContaining("some worker error");
    }

    @Test
    void run_happy_returnsResultWithSection8AndSummary() {
        when(executor.run(any(), any(), anyLong())).thenReturn(new SubprocessResult(0, SECTION8, "", false));
        BacktestResult result = runner.run(req());
        assertThat(result.tradeCount()).isEqualTo(1);
        assertThat(result.realizedPnl()).isEqualByComparingTo("23.5");
        assertThat(result.section8Json()).isEqualTo(SECTION8);
    }

    @Test
    void run_injectsKwikquantApiBaseEnv() {
        // Task 6 fix:worker data_loader 调 Java REST 拉 K 线,必须注入 KWIKQUANT_API_BASE,
        // 否则 worker 用默认 http://kwikquant-app:8080(docker service 名)本地解析失败 → ConnectError
        when(executor.run(any(), any(), anyLong())).thenReturn(new SubprocessResult(0, SECTION8, "", false));
        runner.run(req());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> envCaptor = ArgumentCaptor.forClass(Map.class);
        verify(executor).run(any(), envCaptor.capture(), anyLong());
        Map<String, String> env = envCaptor.getValue();
        assertThat(env.get("KWIKQUANT_API_BASE")).isEqualTo("http://localhost:8080");
        assertThat(env.get("TASK_CONFIG_JSON")).contains("BTC/USDT");
        assertThat(env.get("WORKER_SERVICE_TOKEN")).isEqualTo("token-abc");
    }

    @Test
    void run_timeout_throwsBacktestRunnerException() {
        when(executor.run(any(), any(), anyLong())).thenReturn(new SubprocessResult(-1, "", "", true));
        assertThatThrownBy(() -> runner.run(req())).isInstanceOf(BacktestRunnerException.class);
    }

    @Test
    void run_nonZeroExit_throwsBacktestRunnerException() {
        when(executor.run(any(), any(), anyLong()))
                .thenReturn(new SubprocessResult(1, "", "connection refused", false));
        assertThatThrownBy(() -> runner.run(req())).isInstanceOf(BacktestRunnerException.class);
    }

    @Test
    void run_emptyStdout_throwsBacktestRunnerException() {
        when(executor.run(any(), any(), anyLong())).thenReturn(new SubprocessResult(0, "  ", "", false));
        assertThatThrownBy(() -> runner.run(req())).isInstanceOf(BacktestRunnerException.class);
    }

    @Test
    void run_emptyEquityCurve_returnsZeroRealizedPnl() {
        String section8 = "{\"trades\":[],\"equity_curve\":[],\"metrics\":{}}";
        when(executor.run(any(), any(), anyLong())).thenReturn(new SubprocessResult(0, section8, "", false));
        BacktestResult result = runner.run(req());
        assertThat(result.tradeCount()).isZero();
        assertThat(result.realizedPnl()).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
