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
            executor, objectMapper, "python", "worker_server.py", "http://localhost:8080", 60);

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
                null,
                "BINANCE",
                "1h",
                Instant.parse("2024-01-01T00:00:00Z"),
                Instant.parse("2025-01-01T00:00:00Z"),
                "{}",
                "token-abc",
                "SPOT",
                "pass",
                Map.of("marketSlippageBps", "5"),
                Map.of());
    }

    @Test
    void run_exit2_throwsBacktestNoMarketDataException() {
        // worker 拉空 exit 2 + stderr NO_MARKET_DATA → 抛 BacktestNoMarketDataException
        // (Gateway catch markFailed 7304),非 BacktestRunnerException(7300)
        when(executor.run(any(), any(), any(), anyLong()))
                .thenReturn(SubprocessResult.of(
                        2, "", "NO_MARKET_DATA: OKX SPOT BTC/USDT 1h 2024-01-01~2024-01-02 无历史数据", false));

        assertThatThrownBy(() -> runner.run(req()))
                .isInstanceOf(BacktestNoMarketDataException.class)
                .hasMessageContaining("OKX SPOT BTC/USDT")
                .hasMessageContaining("无历史数据");
    }

    @Test
    void run_exit2_withoutMarker_usesStderrAsMessage() {
        // stderr 无 NO_MARKET_DATA: 标记(兜底)→ 用 stderr 全文
        when(executor.run(any(), any(), any(), anyLong()))
                .thenReturn(SubprocessResult.of(2, "", "some worker error", false));

        assertThatThrownBy(() -> runner.run(req()))
                .isInstanceOf(BacktestNoMarketDataException.class)
                .hasMessageContaining("some worker error");
    }

    @Test
    void run_happy_returnsResultWithSection8AndSummary() {
        when(executor.run(any(), any(), any(), anyLong())).thenReturn(SubprocessResult.of(0, SECTION8, "", false));
        BacktestResult result = runner.run(req());
        assertThat(result.tradeCount()).isEqualTo(1);
        assertThat(result.totalPnl()).isEqualByComparingTo("23.5");
        assertThat(result.section8Json()).isEqualTo(SECTION8);
    }

    @Test
    void run_injectsKwikquantApiBaseEnv() {
        // worker data_loader 调 Java REST 拉 K 线,必须注入 KWIKQUANT_API_BASE,
        // 否则 worker 用默认 http://kwikquant-app:8080(docker service 名)本地解析失败 → ConnectError
        when(executor.run(any(), any(), any(), anyLong())).thenReturn(SubprocessResult.of(0, SECTION8, "", false));
        runner.run(req());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> envCaptor = ArgumentCaptor.forClass(Map.class);
        verify(executor).run(any(), envCaptor.capture(), any(), anyLong());
        Map<String, String> env = envCaptor.getValue();
        assertThat(env.get("KWIKQUANT_API_BASE")).isEqualTo("http://localhost:8080");
        assertThat(env.get("TASK_CONFIG_JSON")).contains("BTC/USDT");
        assertThat(env.get("WORKER_SERVICE_TOKEN")).isEqualTo("token-abc");
        assertThat(env).doesNotContainKeys("WORKER_PG_READONLY_DSN", "POSTGRES_PASSWORD", "DB_PASSWORD");
    }

    @Test
    void run_pairSpecsWireFormat_carriesAllFieldsPythonConsumes() {
        // 跨语言线格式钉死:Python acceptance.PairSpec.from_dict 按 camelCase 键取值,
        // 键名漂移 → from_dict 全 None → minQty/stepSize/maxLeverage 检查静默跳过(闸门失效),
        // 两侧现有测试都不红——此断言是唯一防线。金额必须是字符串(toPlainString,拒 float)
        when(executor.run(any(), any(), any(), anyLong())).thenReturn(SubprocessResult.of(0, SECTION8, "", false));
        BacktestRunRequest base = req();
        BacktestRunRequest withSpecs = new BacktestRunRequest(
                base.taskId(),
                base.strategyId(),
                base.strategyCodeId(),
                base.userId(),
                base.symbol(),
                base.symbols(),
                base.exchange(),
                base.intervalValue(),
                base.startTime(),
                base.endTime(),
                base.parameters(),
                base.serviceToken(),
                "PERP",
                base.strategySource(),
                base.matchingConfig(),
                Map.of(
                        "BTC/USDT",
                        new BacktestRunRequest.PairSpecSnapshot(
                                "BTC/USDT", "PERP", "0.001", "1000", "0.1", "0.001", 100)));

        runner.run(withSpecs);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> envCaptor = ArgumentCaptor.forClass(Map.class);
        verify(executor).run(any(), envCaptor.capture(), any(), anyLong());
        String cfg = envCaptor.getValue().get("TASK_CONFIG_JSON");
        assertThat(cfg).contains("\"pairSpecs\"");
        // from_dict 消费的全部键逐一在场(改名即红)
        assertThat(cfg)
                .contains("\"symbol\":\"BTC/USDT\"")
                .contains("\"marketType\":\"PERP\"")
                .contains("\"minQty\":\"0.001\"")
                .contains("\"maxQty\":\"1000\"")
                .contains("\"tickSize\":\"0.1\"")
                .contains("\"stepSize\":\"0.001\"")
                .contains("\"maxLeverage\":100");
    }

    @Test
    void run_timeout_throwsBacktestRunnerException() {
        when(executor.run(any(), any(), any(), anyLong())).thenReturn(SubprocessResult.of(-1, "", "", true));
        assertThatThrownBy(() -> runner.run(req())).isInstanceOf(BacktestRunnerException.class);
    }

    @Test
    void run_nonZeroExit_throwsBacktestRunnerException() {
        when(executor.run(any(), any(), any(), anyLong()))
                .thenReturn(SubprocessResult.of(1, "", "connection refused", false));
        assertThatThrownBy(() -> runner.run(req())).isInstanceOf(BacktestRunnerException.class);
    }

    @Test
    void run_emptyStdout_throwsBacktestRunnerException() {
        when(executor.run(any(), any(), any(), anyLong())).thenReturn(SubprocessResult.of(0, "  ", "", false));
        assertThatThrownBy(() -> runner.run(req())).isInstanceOf(BacktestRunnerException.class);
    }

    @Test
    void run_emptyEquityCurve_returnsZeroRealizedPnl() {
        String section8 = "{\"trades\":[],\"equity_curve\":[],\"metrics\":{}}";
        when(executor.run(any(), any(), any(), anyLong())).thenReturn(SubprocessResult.of(0, section8, "", false));
        BacktestResult result = runner.run(req());
        assertThat(result.tradeCount()).isZero();
        assertThat(result.totalPnl()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void run_stdoutTruncated_throwsBacktestRunnerException() {
        // stdout 超 64MB 上限被截断 → 结果不可信,明确报错而非解析出残缺 JSON
        when(executor.run(any(), any(), any(), anyLong()))
                .thenReturn(new SubprocessResult(0, "{\"trades\":[", "", false, true));
        assertThatThrownBy(() -> runner.run(req()))
                .isInstanceOf(BacktestRunnerException.class)
                .hasMessageContaining("截断");
    }

    @Test
    void run_passesNullStdinPayload() {
        // 子进程路径配置走 env(TASK_CONFIG_JSON);stdin 通道仅 docker runner 使用
        when(executor.run(any(), any(), any(), anyLong())).thenReturn(SubprocessResult.of(0, SECTION8, "", false));
        runner.run(req());
        verify(executor).run(any(), any(), org.mockito.ArgumentMatchers.isNull(), anyLong());
    }

    @Test
    void run_unconfiguredInterpreter_throwsBacktestRunnerException() {
        // 未配置的 profile 走 @Value :空 默认:bean 可实例化但 run() fail-closed,
        // 而非启动期 context 失败(prod 已由 application-prod.yaml 配内置 venv 默认值)。
        PythonSubprocessBacktestRunner unconfigured =
                new PythonSubprocessBacktestRunner(executor, objectMapper, "", "", "", 60);
        assertThatThrownBy(() -> unconfigured.run(req()))
                .isInstanceOf(BacktestRunnerException.class)
                .hasMessageContaining("未配置");
    }
}
