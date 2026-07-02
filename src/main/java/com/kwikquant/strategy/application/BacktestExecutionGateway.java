package com.kwikquant.strategy.application;

import com.kwikquant.strategy.domain.BacktestTask;
import com.kwikquant.strategy.infrastructure.BacktestTaskMapper;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 回测异步执行网关（独立 Bean，承接 {@code @Async}）。
 *
 * <p>独立成 Bean 是为避免 Spring AOP 同类内部调用陷阱：若 {@code submit} 与 {@code executeAsync} 同在
 * {@link BacktestTaskService}，{@code this.executeAsync} 不走代理，{@code @Async} 失效变同步阻塞 HTTP 线程。
 *
 * <p><b>Wave 6 stub</b>：无 {@link BacktestRunner} 实现时，{@code updateError}("回测执行待 Wave 8 Python Worker
 * 实现") → 推送失败事件。Wave 8 由 Python Worker 适配器实现 {@link BacktestRunner}，注入后自动走真实执行路径。
 */
@Component
public class BacktestExecutionGateway {

    private static final Logger log = LoggerFactory.getLogger(BacktestExecutionGateway.class);

    static final String STUB_MESSAGE = "回测执行待 Wave 8 Python Worker 实现";

    private final BacktestTaskMapper taskMapper;
    private final Optional<BacktestRunner> runner;
    private final SimpMessagingTemplate ws;
    private final ObjectMapper objectMapper;

    public BacktestExecutionGateway(
            BacktestTaskMapper taskMapper,
            Optional<BacktestRunner> runner,
            SimpMessagingTemplate ws,
            ObjectMapper objectMapper) {
        this.taskMapper = taskMapper;
        this.runner = runner;
        this.ws = ws;
        this.objectMapper = objectMapper;
    }

    @Async
    public void executeAsync(long taskId) {
        BacktestTask task = taskMapper.findById(taskId);
        if (task == null) {
            log.warn("Backtest task {} not found, skip execution", taskId);
            return;
        }
        long userId = task.getUserId();
        int updated = taskMapper.updateStatus(taskId, userId, "PENDING", "RUNNING");
        if (updated == 0) {
            log.debug("Backtest task {} already picked up by another thread, skip", taskId);
            return;
        }
        if (runner.isEmpty()) {
            markFailed(task, STUB_MESSAGE);
            return;
        }
        try {
            BacktestResult result = runner.get().run(buildRequest(task));
            String json = objectMapper.writeValueAsString(result);
            taskMapper.updateResult(taskId, userId, json);
            sendEvent(userId, Map.of("taskId", taskId, "status", "COMPLETED"));
        } catch (Exception e) {
            log.error("Backtest execution failed for task {}", taskId, e);
            markFailed(task, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private void markFailed(BacktestTask task, String reason) {
        taskMapper.updateError(task.getId(), task.getUserId(), reason);
        sendEvent(task.getUserId(), Map.of("taskId", task.getId(), "status", "FAILED", "error", reason));
    }

    private void sendEvent(long userId, Map<String, Object> event) {
        // 强转 Object 消除 convertAndSend(D,Object) 与 convertAndSend(Object,Map headers) 的二义
        ws.convertAndSend(destination(userId), (Object) event);
    }

    private static String destination(long userId) {
        return "/topic/backtests/" + userId;
    }

    private static BacktestRunRequest buildRequest(BacktestTask task) {
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
                task.getParameters());
    }
}
