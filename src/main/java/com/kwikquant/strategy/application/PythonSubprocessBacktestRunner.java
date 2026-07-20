package com.kwikquant.strategy.application;

import com.kwikquant.strategy.domain.BacktestNoMarketDataException;
import com.kwikquant.strategy.domain.BacktestRunnerException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 回测子进程 Runner(§3.6,实现 {@link BacktestRunner} SPI)。启动 {@code python worker_server.py --mode=backtest},
 * env 注入 {@code TASK_CONFIG_JSON}(序列化 BacktestRunRequest)+ {@code WORKER_SERVICE_TOKEN};waitFor(timeout)→
 * destroyForcibly;捕获 stdout §8 JSON;解析摘要(realizedPnl from equity_curve, tradeCount from trades);返回
 * {@link BacktestResult}(summary + section8Json)。
 *
 * <p>subprocess 调用委托 {@link SubprocessExecutor}(可 mock,TDD);{@link BacktestRunnerException} 7300 Java 内部异常。
 *
 * <p><b>回测数据获取重构 Task 7</b>:worker 拉空区间 K 线 → exit 2 + stderr {@code NO_MARKET_DATA: ...} →
 * 抛 {@link BacktestNoMarketDataException}(7304,Gateway catch markFailed,非 generic 7300)。
 */
@Component
public class PythonSubprocessBacktestRunner implements BacktestRunner {

    private final SubprocessExecutor executor;
    private final ObjectMapper objectMapper;
    private final String pythonCommand;
    private final String workerScript;
    private final String pgReadonlyDsn;
    private final long timeoutSec;

    public PythonSubprocessBacktestRunner(
            SubprocessExecutor executor,
            ObjectMapper objectMapper,
            @Value("${kwikquant.worker.python-command:python}") String pythonCommand,
            @Value("${kwikquant.worker.script:kwikquant_worker/worker_server.py}") String workerScript,
            @Value("${kwikquant.worker.pg-readonly-dsn:}") String pgReadonlyDsn,
            @Value("${kwikquant.worker.timeout-sec:3600}") long timeoutSec) {
        this.executor = executor;
        this.objectMapper = objectMapper;
        this.pythonCommand = pythonCommand;
        this.workerScript = workerScript;
        this.pgReadonlyDsn = pgReadonlyDsn;
        this.timeoutSec = timeoutSec;
    }

    @Override
    public BacktestResult run(BacktestRunRequest request) {
        String taskConfig = objectMapper.writeValueAsString(request);
        Map<String, String> env = new java.util.HashMap<>();
        env.put("TASK_CONFIG_JSON", taskConfig);
        env.put("WORKER_SERVICE_TOKEN", request.serviceToken() == null ? "" : request.serviceToken());
        env.put("WORKER_PG_READONLY_DSN", pgReadonlyDsn);
        List<String> command = List.of(pythonCommand, workerScript, "--mode=backtest");
        SubprocessResult result = executor.run(command, env, timeoutSec);
        if (result.timedOut()) {
            throw new BacktestRunnerException("worker subprocess timeout (>" + timeoutSec + "s)");
        }
        // exit 2:回测区间无历史数据(worker 拉空 → NO_MARKET_DATA stderr)→ 专用异常,
        // Gateway catch → markFailed 7304(非 generic BacktestRunnerException 7300)
        if (result.exitCode() == 2) {
            throw new BacktestNoMarketDataException(extractNoMarketDataMessage(result.stderr()));
        }
        if (result.exitCode() != 0) {
            throw new BacktestRunnerException("worker exit " + result.exitCode() + ": " + result.stderr());
        }
        String section8 = result.stdout() == null ? "" : result.stdout().trim();
        if (section8.isEmpty()) {
            throw new BacktestRunnerException("worker stdout empty (no §8 JSON)");
        }
        return parseSection8(section8);
    }

    /** 从 worker stderr 提取 {@code NO_MARKET_DATA:} 后内容作 errorMessage;无标记则用 stderr 全文(兜底)。 */
    private static String extractNoMarketDataMessage(String stderr) {
        if (stderr == null || stderr.isBlank()) {
            return "回测区间无历史数据";
        }
        int idx = stderr.indexOf("NO_MARKET_DATA:");
        if (idx < 0) {
            return stderr.trim();
        }
        return stderr.substring(idx + "NO_MARKET_DATA:".length()).trim();
    }

    BacktestResult parseSection8(String section8Json) {
        JsonNode root = objectMapper.readTree(section8Json);
        JsonNode trades = root.path("trades");
        int tradeCount = trades.isArray() ? trades.size() : 0;
        BigDecimal realizedPnl = extractRealizedPnl(root);
        return new BacktestResult(realizedPnl, tradeCount, section8Json);
    }

    private BigDecimal extractRealizedPnl(JsonNode root) {
        JsonNode eq = root.path("equity_curve");
        if (!eq.isArray() || eq.isEmpty()) return BigDecimal.ZERO;
        BigDecimal first = new BigDecimal(eq.get(0).path("equity").asText("0"));
        BigDecimal last = new BigDecimal(eq.get(eq.size() - 1).path("equity").asText("0"));
        return last.subtract(first);
    }
}
