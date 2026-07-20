package com.kwikquant.strategy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.kwikquant.strategy.domain.BacktestRunnerException;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class PythonSubprocessBacktestRunnerTest {

    private final SubprocessExecutor executor = mock(SubprocessExecutor.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PythonSubprocessBacktestRunner runner = new PythonSubprocessBacktestRunner(
            executor, objectMapper, "python", "worker_server.py", "host=localhost dbname=kwikquant", 60);

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
                "SPOT");
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
