package com.kwikquant.strategy.application;

import com.kwikquant.report.application.ReportService;
import com.kwikquant.shared.infra.BacktestLedgerLifecycle;
import com.kwikquant.shared.infra.WorkerTokenService;
import com.kwikquant.strategy.domain.BacktestTask;
import com.kwikquant.strategy.domain.BacktestTaskStatus;
import com.kwikquant.strategy.infrastructure.BacktestTaskMapper;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 回测异步执行网关(独立 Bean,承接 {@code @Async},避同类 AOP 陷阱)。
 *
 * <p><b>Wave 8 §3.6 真实化</b>:注入 {@link PythonSubprocessBacktestRunner}(BacktestRunner SPI)→ 自动走真实路径
 * (Wave 6 {@code Optional<BacktestRunner>} 分支)。流程:CAS PENDING→RUNNING → issueToken(BACKTEST) → initLedger →
 * try{runner.run → ReportService.submitBacktestResult(§8) → updateResult(summary) + COMPLETED + WS}catch{markFailed}
 * finally{cleanupLedger, revokeToken}(防账本+token 泄露,C4/N4/R6 修复)。
 */
@Component
public class BacktestExecutionGateway {

    private static final Logger log = LoggerFactory.getLogger(BacktestExecutionGateway.class);

    static final String STUB_MESSAGE = "回测执行待 Wave 8 Python Worker 实现";
    private static final BigDecimal DEFAULT_INITIAL_CAPITAL = new BigDecimal("100000");

    private final BacktestTaskMapper taskMapper;
    private final Optional<BacktestRunner> runner;
    private final SimpMessagingTemplate ws;
    private final ObjectMapper objectMapper;
    private final WorkerTokenService workerTokenService;
    private final BacktestLedgerLifecycle ledgerLifecycle;
    private final ReportService reportService;

    public BacktestExecutionGateway(
            BacktestTaskMapper taskMapper,
            Optional<BacktestRunner> runner,
            SimpMessagingTemplate ws,
            ObjectMapper objectMapper,
            WorkerTokenService workerTokenService,
            BacktestLedgerLifecycle ledgerLifecycle,
            ReportService reportService) {
        this.taskMapper = taskMapper;
        this.runner = runner;
        this.ws = ws;
        this.objectMapper = objectMapper;
        this.workerTokenService = workerTokenService;
        this.ledgerLifecycle = ledgerLifecycle;
        this.reportService = reportService;
    }

    @Async
    public void executeAsync(long taskId) {
        BacktestTask task = taskMapper.findById(taskId);
        if (task == null) {
            log.warn("Backtest task {} not found, skip execution", taskId);
            return;
        }
        long userId = task.getUserId();
        int updated = taskMapper.updateStatus(
                taskId, userId, BacktestTaskStatus.PENDING.name(), BacktestTaskStatus.RUNNING.name());
        if (updated == 0) {
            log.debug("Backtest task {} already picked up by another thread, skip", taskId);
            return;
        }
        if (runner.isEmpty()) {
            markFailed(task, STUB_MESSAGE);
            return;
        }

        // token 声明在 try 外部,防御 initLedger/后续任何抛出时 finally 也能 revoke(Round-5 MAJOR 3)
        String token = null;
        try {
            token = workerTokenService.issueToken(
                    task.getStrategyId(), WorkerTokenService.TASK_TYPE_BACKTEST, userId, task.getExchange());
            ledgerLifecycle.initLedger(taskId, extractInitialCapital(task.getParameters()));
            BacktestResult result = runner.get().run(buildRequest(task, token));
            long reportId = reportService.submitBacktestResult(userId, result.section8Json());
            String summary = objectMapper.writeValueAsString(
                    Map.of("realizedPnl", result.realizedPnl(), "tradeCount", result.tradeCount()));
            taskMapper.updateResult(taskId, userId, summary, reportId);
            sendEvent(userId, Map.of("taskId", taskId, "status", BacktestTaskStatus.COMPLETED.name()));
        } catch (Exception e) {
            log.error("Backtest execution failed for task {}", taskId, e);
            markFailed(task, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        } finally {
            ledgerLifecycle.cleanupLedger(taskId);
            if (token != null) {
                workerTokenService.revokeToken(token);
            }
        }
    }

    private void markFailed(BacktestTask task, String reason) {
        taskMapper.updateError(task.getId(), task.getUserId(), reason);
        sendEvent(
                task.getUserId(),
                Map.of("taskId", task.getId(), "status", BacktestTaskStatus.FAILED.name(), "error", reason));
    }

    private void sendEvent(long userId, Map<String, Object> event) {
        // 强转 Object 消除 convertAndSend(D,Object) 与 convertAndSend(Object,Map headers) 的二义
        ws.convertAndSend(destination(userId), (Object) event);
    }

    private static String destination(long userId) {
        return "/topic/backtests/" + userId;
    }

    private BigDecimal extractInitialCapital(String parameters) {
        if (parameters == null || parameters.isBlank()) return DEFAULT_INITIAL_CAPITAL;
        try {
            JsonNode node = objectMapper.readTree(parameters);
            if (node != null && node.has("initial_capital")) {
                return new BigDecimal(node.get("initial_capital").asText(DEFAULT_INITIAL_CAPITAL.toPlainString()));
            }
        } catch (Exception e) {
            // 解析失败用默认值
        }
        return DEFAULT_INITIAL_CAPITAL;
    }

    private static BacktestRunRequest buildRequest(BacktestTask task, String serviceToken) {
        return new BacktestRunRequest(
                task.getId(),
                task.getStrategyId(),
                task.getStrategyCodeId(),
                task.getUserId(),
                task.getSymbol(),
                task.getExchange(),
                task.getIntervalValue(),
                task.getStartTime(),
                task.getEndTime(),
                task.getParameters(),
                serviceToken);
    }
}
