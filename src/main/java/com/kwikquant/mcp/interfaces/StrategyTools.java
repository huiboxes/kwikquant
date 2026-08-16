package com.kwikquant.mcp.interfaces;

import com.kwikquant.account.application.ExchangeAccountService;
import com.kwikquant.account.domain.ExchangeAccount;
import com.kwikquant.mcp.application.McpConfirmTokenService;
import com.kwikquant.mcp.application.McpScopeGuard;
import com.kwikquant.mcp.interfaces.view.BacktestReportPageView;
import com.kwikquant.mcp.interfaces.view.BacktestResultView;
import com.kwikquant.mcp.interfaces.view.ComparisonView;
import com.kwikquant.mcp.interfaces.view.ConfirmRequiredView;
import com.kwikquant.mcp.interfaces.view.StartLiveTradingPreview;
import com.kwikquant.mcp.interfaces.view.StrategyView;
import com.kwikquant.report.application.ComparisonResult;
import com.kwikquant.report.application.ReportComparisonService;
import com.kwikquant.report.application.ReportService;
import com.kwikquant.report.domain.BacktestReport;
import com.kwikquant.shared.infra.McpToolParamInvalidException;
import com.kwikquant.shared.infra.SecurityUtils;
import com.kwikquant.shared.types.McpTokenScope;
import com.kwikquant.shared.types.PageDto;
import com.kwikquant.shared.types.PageQuery;
import com.kwikquant.strategy.application.BacktestTaskService;
import com.kwikquant.strategy.application.StrategyCrudService;
import com.kwikquant.strategy.application.StrategyLifecycleService;
import com.kwikquant.strategy.domain.BacktestTask;
import com.kwikquant.strategy.domain.BacktestTaskStatus;
import com.kwikquant.strategy.domain.StrategyDefinition;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * MCP 策略工具组。5 个 {@code @McpTool}：run_backtest / list_backtests / compare_backtests /
 * start_paper_trading / start_live_trading。
 *
 * <p><b>run_backtest 双模式</b>（提交 + 续查合一，免独立 get_backtest_status 工具）：
 * <ul>
 *   <li>提交模式（{@code strategyId} 非空）：{@link BacktestTaskService#submit} 返 taskId → 轮询
 *       {@link BacktestTaskService#getOwned} 最多 {@code pollMaxAttempts} 次（间隔
 *       {@code pollIntervalMs}，默认 3s×5≈15s）；COMPLETED 返 {@link BacktestResultView#from}，
 *       FAILED 返 status:FAILED+errorMessage，超时仍 RUNNING 返 {@link BacktestResultView#running}
 *       （envelope code=0 降级，hint 引导 Agent 再调 {@code run_backtest(taskId=...)} 续查）。
 *   <li>查询模式（{@code taskId} 非空且 {@code strategyId} 为空）：直接 getOwned 一次返当前状态（不轮询）。
 * </ul>
 *
 * <p><b>BacktestTask 无 reportId</b>（结果存 {@link BacktestTask#getResult()} 回测结果 JSON String），故
 * COMPLETED 直接用 task.result，不调 ReportService.getById（list_backtests 才走 ReportService）。
 *
 * <p><b>start_paper/live</b>：{@link StrategyDefinition} 无 accountId 字段，由 Agent 显式传 accountId；
 * 校验 {@code account.getExchange().name() equalsIgnoreCase strategy.getExchange()}（Exchange 枚举 vs String）
 * + {@code account.isPaperTrading()}（paper 工具须 true，live 工具须 false）；不匹配抛 10002。
 * start_live 为实盘真实下单不可逆高危，走**两阶段 confirmToken**（第一阶段返预览+令牌零副作用，
 * 第二阶段携令牌复述参数执行；旧裸 boolean confirm 已废弃）。scope 要求 LIVE。
 *
 * <p>轮询 sleep 间隔/次数可配置（test profile 注入 0 间隔避免阻塞）。
 */
@Component
public class StrategyTools {

    private static final String TOOL_START_LIVE = "start_live_trading";

    private final BacktestTaskService backtestTaskService;
    private final ReportService reportService;
    private final ReportComparisonService comparisonService;
    private final StrategyCrudService strategyCrudService;
    private final StrategyLifecycleService lifecycleService;
    private final ExchangeAccountService accountService;
    private final ObjectMapper objectMapper;
    private final McpScopeGuard scopeGuard;
    private final McpConfirmTokenService confirmTokenService;
    private final long pollIntervalMs;
    private final int pollMaxAttempts;

    public StrategyTools(
            BacktestTaskService backtestTaskService,
            ReportService reportService,
            ReportComparisonService comparisonService,
            StrategyCrudService strategyCrudService,
            StrategyLifecycleService lifecycleService,
            ExchangeAccountService accountService,
            ObjectMapper objectMapper,
            McpScopeGuard scopeGuard,
            McpConfirmTokenService confirmTokenService,
            @Value("${kwikquant.mcp.backtest.poll-interval-ms:3000}") long pollIntervalMs,
            @Value("${kwikquant.mcp.backtest.poll-max-attempts:5}") int pollMaxAttempts) {
        this.backtestTaskService = backtestTaskService;
        this.reportService = reportService;
        this.comparisonService = comparisonService;
        this.strategyCrudService = strategyCrudService;
        this.lifecycleService = lifecycleService;
        this.accountService = accountService;
        this.objectMapper = objectMapper;
        this.scopeGuard = scopeGuard;
        this.confirmTokenService = confirmTokenService;
        this.pollIntervalMs = pollIntervalMs;
        this.pollMaxAttempts = pollMaxAttempts;
    }

    @McpTool(
            name = "run_backtest",
            description = "提交回测并等待结果(默认等待约 poll-interval-ms×poll-max-attempts≈15s, 未结束返 status=RUNNING+taskId). "
                    + "提交模式: 传 strategyId+symbol+timeframe+start+end+params; "
                    + "查询模式: 传 taskId(上次返回的)续查. COMPLETED返结果JSON, FAILED返errorMessage, "
                    + "RUNNING+hint 时稍后重试(非错误).",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false))
    public BacktestResultView runBacktest(
            @McpToolParam(description = "策略ID(提交模式)", required = false) Long strategyId,
            @McpToolParam(description = "回测任务ID(查询模式,超时续查用)", required = false) Long taskId,
            @McpToolParam(description = "交易对 BTC/USDT", required = false) String symbol,
            @McpToolParam(description = "K线周期 1m/5m/15m/1h/4h/1d", required = false) String timeframe,
            @McpToolParam(description = "起始 ISO-8601", required = false) String start,
            @McpToolParam(description = "结束 ISO-8601", required = false) String end,
            @McpToolParam(description = "策略参数 JSON 对象", required = false) Map<String, Object> params) {
        scopeGuard.require(McpTokenScope.BACKTEST);
        long userId = SecurityUtils.currentUserId();
        if (taskId != null && strategyId == null) {
            // 查询模式：直接查一次当前状态
            BacktestTask t = backtestTaskService.getOwned(taskId, userId);
            return BacktestResultView.from(t);
        }
        if (strategyId == null) {
            throw new McpToolParamInvalidException("run_backtest requires strategyId (submit) or taskId (query)");
        }
        Instant startTime = parseParam(start, Instant::parse, "start");
        Instant endTime = parseParam(end, Instant::parse, "end");
        String paramsJson = toJson(params);
        BacktestTask task =
                backtestTaskService.submit(strategyId, userId, symbol, null, timeframe, startTime, endTime, paramsJson);
        return pollUntilDone(task.getId(), userId);
    }

    @McpTool(
            name = "list_backtests",
            description = "列出历史回测结果(分页). symbol 可省略查全部. 返回绩效指标摘要列表.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true))
    public BacktestReportPageView listBacktests(
            @McpToolParam(description = "交易对过滤(可省略)", required = false) String symbol,
            @McpToolParam(description = "页码(从1)", required = false) Integer page,
            @McpToolParam(description = "每页大小", required = false) Integer pageSize) {
        scopeGuard.require(McpTokenScope.READ);
        long userId = SecurityUtils.currentUserId();
        PageQuery pq = PageQuery.ofStandard(page, pageSize);
        PageDto<BacktestReport> result = reportService.listByUser(userId, symbol, pq);
        return BacktestReportPageView.from(result);
    }

    @McpTool(
            name = "compare_backtests",
            description = "对比多次回测结果. 传 reportId 列表, 返回排序矩阵.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true))
    public ComparisonView compareBacktests(@McpToolParam(description = "BacktestReport ID 列表") List<Long> reportIds) {
        scopeGuard.require(McpTokenScope.READ);
        long userId = SecurityUtils.currentUserId();
        ComparisonResult result = comparisonService.compare(reportIds, userId);
        return ComparisonView.from(result);
    }

    @McpTool(
            name = "start_paper_trading",
            description = "启动模拟盘. 须传 accountId, 校验 account.exchange==strategy.exchange 且 account.paperTrading=true. "
                    + "不匹配抛 10002.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false))
    public StrategyView startPaperTrading(
            @McpToolParam(description = "策略ID") Long strategyId,
            @McpToolParam(description = "模拟盘账户ID(paperTrading=true)") Long accountId) {
        scopeGuard.require(McpTokenScope.TRADE);
        long userId = SecurityUtils.currentUserId();
        StrategyDefinition strategy = strategyCrudService.getOwned(strategyId, userId);
        ExchangeAccount account = accountService.getOwned(accountId, userId);
        verifyExchangeMatch(strategy, account);
        if (!account.isPaperTrading()) {
            throw new McpToolParamInvalidException(
                    "start_paper_trading requires paper-trading account (paperTrading=true), got accountId="
                            + accountId);
        }
        return StrategyView.from(lifecycleService.start(strategyId, userId, accountId));
    }

    @McpTool(
            name = "start_live_trading",
            description = "启动实盘(真实资金, 不可逆高危). **两阶段确认**:不带 confirmToken 调用先返预览+令牌(零副作用),"
                    + "向用户确认后复述相同参数+confirmToken 再调用才执行. 须 accountId, 校验 "
                    + "account.exchange==strategy.exchange 且 account.paperTrading=false. 不匹配抛 10002.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = true))
    public Object startLiveTrading(
            @McpToolParam(description = "策略ID") Long strategyId,
            @McpToolParam(description = "实盘账户ID(paperTrading=false)") Long accountId,
            @McpToolParam(description = "两阶段确认令牌(第二阶段传第一阶段返回值)", required = false) String confirmToken) {
        scopeGuard.require(McpTokenScope.LIVE);
        long userId = SecurityUtils.currentUserId();
        StrategyDefinition strategy = strategyCrudService.getOwned(strategyId, userId);
        ExchangeAccount account = accountService.getOwned(accountId, userId);
        verifyExchangeMatch(strategy, account);
        if (account.isPaperTrading()) {
            throw new McpToolParamInvalidException(
                    "start_live_trading requires live account (paperTrading=false), got paper accountId=" + accountId);
        }
        String canonical = canonical(strategyId, accountId);
        if (confirmToken == null || confirmToken.isBlank()) {
            var issue = confirmTokenService.issue(userId, TOOL_START_LIVE, canonical);
            return new ConfirmRequiredView(
                    TOOL_START_LIVE,
                    issue.token(),
                    issue.expiresInSec(),
                    new StartLiveTradingPreview(
                            strategyId,
                            strategy.getName(),
                            accountId,
                            account.getLabel(),
                            strategy.getExchange(),
                            strategy.getSymbol(),
                            strategy.getIntervalValue()));
        }
        confirmTokenService.consume(userId, TOOL_START_LIVE, canonical, confirmToken);
        return StrategyView.from(lifecycleService.start(strategyId, userId, accountId));
    }

    /** 规范化参数串(指纹用):解析后值拼接,保证"复述相同参数"产生相同指纹。 */
    private static String canonical(Object... parts) {
        StringBuilder sb = new StringBuilder();
        for (Object p : parts) {
            sb.append(p == null ? "∅" : String.valueOf(p)).append('|');
        }
        return sb.toString();
    }

    /** 轮询至 COMPLETED/FAILED 或超时降级。第 1 次立即查（submit 后任务可能已瞬完），后续 sleep。 */
    private BacktestResultView pollUntilDone(long taskId, long userId) {
        for (int i = 0; i < pollMaxAttempts; i++) {
            if (i > 0) {
                sleepQuiet(pollIntervalMs);
            }
            BacktestTask t = backtestTaskService.getOwned(taskId, userId);
            BacktestTaskStatus status = t.getStatus();
            if (status == BacktestTaskStatus.COMPLETED) {
                return BacktestResultView.from(t);
            }
            if (status == BacktestTaskStatus.FAILED) {
                return BacktestResultView.from(t);
            }
            // PENDING/RUNNING 继续
        }
        return BacktestResultView.running(
                taskId, "backtest still running, call run_backtest(taskId=" + taskId + ") to continue");
    }

    private static void verifyExchangeMatch(StrategyDefinition strategy, ExchangeAccount account) {
        if (!account.getExchange().name().equalsIgnoreCase(strategy.getExchange())) {
            throw new McpToolParamInvalidException("strategy and account must share exchange: strategy="
                    + strategy.getExchange() + " account=" + account.getExchange());
        }
    }

    private String toJson(Map<String, Object> params) {
        if (params == null || params.isEmpty()) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(params);
        } catch (JacksonException e) {
            throw new McpToolParamInvalidException("invalid params: " + e.getMessage());
        }
    }

    private static void sleepQuiet(long ms) {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            // 还原中断标志，返当前状态（调用方 pollUntilDone 下一轮 getOwned 拿最新）
            Thread.currentThread().interrupt();
        }
    }

    private static <T> T parseParam(String raw, Function<String, T> parser, String desc) {
        try {
            return parser.apply(raw);
        } catch (RuntimeException e) {
            throw new McpToolParamInvalidException("invalid " + desc + ": " + raw);
        }
    }
}
