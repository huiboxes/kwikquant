package com.kwikquant.strategy.application;

import com.kwikquant.report.application.ReportService;
import com.kwikquant.shared.infra.WorkerTokenService;
import com.kwikquant.strategy.domain.BacktestFailureCategory;
import com.kwikquant.strategy.domain.BacktestNoMarketDataException;
import com.kwikquant.strategy.domain.BacktestTask;
import com.kwikquant.strategy.domain.BacktestTaskStatus;
import com.kwikquant.strategy.domain.StrategyCode;
import com.kwikquant.strategy.infrastructure.BacktestTaskMapper;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 回测异步执行网关(独立 Bean,承接 {@code @Async},避同类 AOP 陷阱)。
 *
 * <p>注入 {@link BacktestRunner}(SPI,按 {@code kwikquant.backtest.runner} 条件装配:
 * docker = 隔离容器执行(prod)/ subprocess = app 内子进程(dev/test))。流程:CAS PENDING→RUNNING →
 * issueBacktestToken(taskId) → try{runner.run → ReportService.submitBacktestResult →
 * updateResult(summary) + COMPLETED + WS}catch{markFailed}finally{revokeToken}。
 *
 * <p><b>撮合本地化</b>:撮合在 Python worker 本地引擎执行
 * ({@code kwikquant_worker/backtest/matching.py}),Java 侧不维护回测虚拟账本;
 * {@link #defaultMatchingConfig()} 把撮合配置快照随 {@link BacktestRunRequest} 下发给 worker
 * 实际消费并写入报告。
 *
 * <p><b>回测数据获取</b>:buildRequest 从任务快照 {@link BacktestTask#getMarketType()} 填入
 * {@link BacktestRunRequest#marketType()}(V54 落 backtest_tasks.market_type,提交时冻结,排队期间
 * 策略变更不影响执行语义),Worker 据此调 {@code GET /api/v1/backtests/{taskId}/klines?marketType=...}。
 * worker 拉空 → exit 2 → Runner 抛 {@link BacktestNoMarketDataException} → catch markFailed(7304)。
 *
 * <p><b>策略源码传递</b>:buildRequest 调 {@link StrategyCodeService#getOwnedCode} 取
 * {@code strategy_codes.source_code} 填入 {@link BacktestRunRequest#strategySource()},Worker exec
 * 实例化用户 Strategy 子类。源码空 → 抛,catch markFailed(避免空策略跑完产出
 * "区间内 0 信号"的误导结果)。
 */
@Component
public class BacktestExecutionGateway {

    private static final Logger log = LoggerFactory.getLogger(BacktestExecutionGateway.class);

    private final BacktestTaskMapper taskMapper;
    private final BacktestRunner runner;
    private final SimpMessagingTemplate ws;
    private final ObjectMapper objectMapper;
    private final WorkerTokenService workerTokenService;
    private final ReportService reportService;
    private final StrategyCodeService codeService;

    public BacktestExecutionGateway(
            BacktestTaskMapper taskMapper,
            BacktestRunner runner,
            SimpMessagingTemplate ws,
            ObjectMapper objectMapper,
            WorkerTokenService workerTokenService,
            ReportService reportService,
            StrategyCodeService codeService) {
        this.taskMapper = taskMapper;
        this.runner = runner;
        this.ws = ws;
        this.objectMapper = objectMapper;
        this.workerTokenService = workerTokenService;
        this.reportService = reportService;
        this.codeService = codeService;
    }

    /**
     * 回测撮合配置快照(随 {@link BacktestRunRequest#matchingConfig()} 下发,worker 本地撮合引擎
     * 实际消费 + 写入报告 reproducibility)。
     *
     * <p><b>跨语言契约</b>:值必须与 Python {@code kwikquant_worker/backtest/matching.py}
     * {@code MatchConfig.defaults()} 一致;单一真相源是 {@code docs/matching-spec.md} §2,
     * 差分 fixtures({@code tests/fixtures/matching})在 CI 拦截两侧漂移。
     */
    static Map<String, Object> defaultMatchingConfig() {
        return Map.of(
                "fidelity", "FAST",
                "marketSlippageBps", "5",
                "partialFillEnabled", false,
                "makerFeeRate", "0.001",
                "takerFeeRate", "0.002");
    }

    @Async("backtestExecutor")
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

        // token 声明在 try 外部,防御后续任何抛出时 finally 也能 revoke
        String token = null;
        BacktestResult result = null;
        try {
            // BACKTEST token 绑定 taskId,不绑 accountId;撮合本地化后 worker 仅用它拉数据/报进度。
            token = workerTokenService.issueBacktestToken(task.getStrategyId(), taskId, userId, task.getExchange());
            // 快照语义:marketType 以任务提交时冻结值为准(V54),不再运行期回读策略——排队期间策略被改
            // 不影响已入队任务。防御分支:历史存量/异常数据快照为 PERP 时拒执行(markFailed)。
            if ("PERP".equalsIgnoreCase(task.getMarketType())) {
                throw new IllegalArgumentException(
                        "PERP 回测暂不可用：Python 策略 API 尚未完整支持 positionEffect/leverage/marginMode");
            }
            result = runner.run(buildRequest(task, token));
            long reportId = reportService.submitBacktestResult(userId, result.section8Json());
            // summary.totalPnl = equity 末−首(绝对额,含未实现);收益率口径在 report.totalReturn
            String summary = objectMapper.writeValueAsString(
                    Map.of("totalPnl", result.totalPnl(), "tradeCount", result.tradeCount()));
            taskMapper.updateResult(taskId, userId, summary, reportId);
            sendEvent(
                    userId,
                    Map.of(
                            "taskId", taskId,
                            "status", BacktestTaskStatus.COMPLETED.name(),
                            "timestamp", Instant.now().toString()));
        } catch (BacktestNoMarketDataException e) {
            // worker 拉空(exit 2)→ markFailed 7304,errorMessage 含区间信息供前端展示
            log.warn("Backtest task {} no market data: {}", taskId, e.getMessage());
            markFailed(task, e.getMessage(), BacktestFailureCategory.MARKET_DATA);
        } catch (Exception e) {
            // 回测失败时若已拿到 section8(含 on_bar warnings),附加到 errorMessage 供前端/DB 诊断
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            if (result != null) {
                try {
                    var warns = objectMapper.readTree(result.section8Json()).path("warnings");
                    if (warns.isArray() && !warns.isEmpty()) {
                        msg = msg + " | on_bar warnings: " + warns;
                    }
                } catch (Exception ignored) { // noqa: 纯诊断,parse 失败不掩盖原异常
                }
            }
            log.error("Backtest execution failed for task {}", taskId, e);
            markFailed(task, msg, BacktestFailureCategory.classify(msg));
        } finally {
            if (token != null) {
                workerTokenService.revokeToken(token);
            }
        }
    }

    private void markFailed(BacktestTask task, String reason, BacktestFailureCategory category) {
        taskMapper.updateError(task.getId(), task.getUserId(), reason, category.name());
        sendEvent(task.getUserId(), failedEvent(task.getId(), reason, category));
    }

    /**
     * FAILED WS 事件:error 为原始原因(诊断用),category + userMessage 为分类映射的用户可读文案
     * (与 REST 任务 DTO 同一映射 {@link BacktestFailureCategory#userMessage()})。WS 即时推送与轮询
     * 兜底两条路径文案一致,前端优先 toast userMessage。userMessage 可能为 null(INTERNAL 未识别),
     * Map.of 不允许 null 值,故用 HashMap 仅非空时携带。
     */
    private static Map<String, Object> failedEvent(long taskId, String reason, BacktestFailureCategory category) {
        Map<String, Object> event = new HashMap<>();
        event.put("taskId", taskId);
        event.put("status", BacktestTaskStatus.FAILED.name());
        event.put("error", reason);
        event.put("category", category.name());
        event.put("timestamp", Instant.now().toString());
        String userMessage = category.userMessage();
        if (userMessage != null) {
            event.put("userMessage", userMessage);
        }
        return event;
    }

    /**
     * 恢复路径标失败(BacktestTaskRecovery 调用):updateError 带 {@code status='RUNNING'} 守卫,
     * 已被正常流程推进终态的任务不会被误写。返 true = 确实由 RUNNING 转为 FAILED(并广播 WS);
     * false = 任务已不在 RUNNING(并发完成/失败),跳过。
     */
    public boolean markFailedByRecovery(long taskId, long userId, String reason) {
        BacktestFailureCategory category = BacktestFailureCategory.classify(reason);
        int updated = taskMapper.updateError(taskId, userId, reason, category.name());
        if (updated == 0) {
            return false;
        }
        sendEvent(userId, failedEvent(taskId, reason, category));
        return true;
    }

    private void sendEvent(long userId, Map<String, Object> event) {
        // 强转 Object 消除 convertAndSend(D,Object) 与 convertAndSend(Object,Map headers) 的二义
        ws.convertAndSend(destination(userId), (Object) event);
    }

    private static String destination(long userId) {
        return "/topic/backtests/" + userId;
    }

    private BacktestRunRequest buildRequest(BacktestTask task, String serviceToken) {
        StrategyCode code = codeService.getOwnedCode(task.getStrategyId(), task.getUserId(), task.getStrategyCodeId());
        if (code.getSourceCode() == null || code.getSourceCode().isBlank()) {
            // 代码版本不存在/源码空 → 明确报错,不再静默走 baseline 空 on_bar 导致"0 信号"误导
            throw new IllegalStateException("策略代码版本不存在或源码为空: strategyCodeId=" + task.getStrategyCodeId());
        }
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
                serviceToken,
                task.getMarketType(),
                code.getSourceCode(),
                defaultMatchingConfig());
    }
}
