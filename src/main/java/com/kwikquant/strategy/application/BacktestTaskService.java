package com.kwikquant.strategy.application;

import com.kwikquant.report.application.ReportService;
import com.kwikquant.shared.infra.OwnershipCheck;
import com.kwikquant.shared.infra.ResourceStateConflictException;
import com.kwikquant.shared.infra.SecurityUtils;
import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.Interval;
import com.kwikquant.shared.types.MarketType;
import com.kwikquant.strategy.domain.BacktestTask;
import com.kwikquant.strategy.domain.BacktestTaskNotFoundException;
import com.kwikquant.strategy.domain.BacktestTaskStatus;
import com.kwikquant.strategy.domain.NoPublishedStrategyCodeException;
import com.kwikquant.strategy.domain.StrategyCode;
import com.kwikquant.strategy.domain.StrategyDefinition;
import com.kwikquant.strategy.infrastructure.BacktestTaskMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

/**
 * 回测任务服务。提交 PENDING 任务 → 触发异步执行；状态/结果查询。
 *
 * <p><b>submit 不加 {@code @Transactional}</b>：submit 仅一次 insert，无跨写一致性需求。
 * 显式事务反而引发 {@code @Async} 读未提交问题——{@code executionGateway.executeAsync} 在新线程跑，若 submit
 * 持有事务未提交，异步线程 {@code findById} 读不到任务→skip。配额 count+insert 的原子性由
 * {@link BacktestQuotaGuard#insertWithinQuota}（独立 Bean，自有事务，返回前提交）承担，异步可见性不受影响。
 *
 * <p><b>范围</b>：只建任务框架（提交/状态/结果/WebSocket），实际执行走
 * {@link BacktestExecutionGateway} → Python Worker(回测不在此模块)。
 */
@Service
public class BacktestTaskService {

    private final BacktestTaskMapper taskMapper;
    private final StrategyCrudService crudService;
    private final StrategyCodeService codeService;
    private final BacktestExecutionGateway executionGateway;
    private final SimpMessagingTemplate ws;
    private final ReportService reportService;
    private final BacktestQuotaGuard quotaGuard;
    private final long maxBars;

    public BacktestTaskService(
            BacktestTaskMapper taskMapper,
            StrategyCrudService crudService,
            StrategyCodeService codeService,
            BacktestExecutionGateway executionGateway,
            SimpMessagingTemplate ws,
            ReportService reportService,
            BacktestQuotaGuard quotaGuard,
            @Value("${kwikquant.backtest.max-bars:100000}") long maxBars) {
        this.taskMapper = taskMapper;
        this.crudService = crudService;
        this.codeService = codeService;
        this.executionGateway = executionGateway;
        this.ws = ws;
        this.reportService = reportService;
        this.quotaGuard = quotaGuard;
        this.maxBars = maxBars;
    }

    public BacktestTask submit(
            long strategyId,
            long userId,
            String symbol,
            String exchange,
            String intervalValue,
            Instant startTime,
            Instant endTime,
            String parameters) {
        StrategyDefinition strategy = crudService.getOwned(strategyId, userId);
        if ("PERP".equalsIgnoreCase(strategy.getMarketType())) {
            throw new IllegalArgumentException("PERP 回测暂不可用：策略 API 尚未完整支持 positionEffect/leverage/marginMode");
        }
        StrategyCode code = codeService.getPublishedCode(strategyId);
        if (code == null) {
            throw new NoPublishedStrategyCodeException(strategyId);
        }
        String resolvedSymbol = symbol != null ? symbol : strategy.getSymbol();
        String resolvedExchange = exchange != null ? exchange : strategy.getExchange();
        String resolvedInterval = intervalValue != null ? intervalValue : strategy.getIntervalValue();
        // 轻量校验:exchange 必须是真实枚举(非 PAPER,模拟盘 exchange='OKX' 非 PAPER)、interval 合法、
        // start<end、bar 数上限、symbol 非空。非法抛 IllegalArgumentException(@RestControllerAdvice 转
        // 3001 VALIDATION_FAILED / 400)。
        validateBacktestParams(resolvedSymbol, resolvedExchange, resolvedInterval, startTime, endTime);
        // marketType 快照:提交时冻结策略市场类型,V54 落 backtest_tasks.market_type。排队期间策略被改
        // 不影响执行语义(worker 与 klines 端点均以任务快照为准)。
        String marketTypeSnapshot = snapshotMarketType(strategy);
        BacktestTask task = BacktestTask.create(
                strategyId,
                userId,
                code.getId(),
                resolvedSymbol,
                resolvedExchange,
                marketTypeSnapshot,
                resolvedInterval,
                startTime,
                endTime,
                parameters);
        // 并发配额:advisory lock + count + insert 同事务,消除并发提交 write skew(见 BacktestQuotaGuard)。
        quotaGuard.insertWithinQuota(task);
        executionGateway.executeAsync(task.getId());
        return task;
    }

    /** marketType 快照(空兜底 SPOT;上游已拒 PERP,快照当前只可能是 SPOT,留兜底防未来放开)。 */
    private static String snapshotMarketType(StrategyDefinition strategy) {
        String mt = strategy.getMarketType();
        return (mt == null || mt.isBlank()) ? "SPOT" : mt.toUpperCase();
    }

    private void validateBacktestParams(
            String symbol, String exchange, String intervalValue, Instant startTime, Instant endTime) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("backtest symbol must not be blank");
        }
        if (exchange == null || exchange.isBlank()) {
            throw new IllegalArgumentException("backtest exchange must not be blank");
        }
        Exchange ex;
        try {
            ex = Exchange.valueOf(exchange);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("backtest exchange invalid: " + exchange);
        }
        if (ex == Exchange.PAPER) {
            throw new IllegalArgumentException(
                    "backtest exchange must not be PAPER (use real exchange like OKX/BINANCE)");
        }
        if (startTime == null || endTime == null || !startTime.isBefore(endTime)) {
            throw new IllegalArgumentException("backtest startTime must be before endTime");
        }
        // interval 枚举校验(此前完全不校验,非法值能进 DB 直到 worker 拉数据才失败)
        Interval interval;
        try {
            interval = Interval.fromCcxt(intervalValue);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("backtest intervalValue invalid: " + intervalValue);
        }
        // bar 数上限:防"6 年 × 1m ≈ 315 万根"级请求拖垮交易所限频/缓存/JVM
        // (worker 单次全量拉取 + Java 侧 API 分页 3000+ 次)。按 interval 折算,配置统一 max-bars。
        long bars = Duration.between(startTime, endTime).toMillis() / interval.toMillis();
        if (bars > maxBars) {
            throw new IllegalArgumentException(
                    "backtest range too large: ~" + bars + " bars exceeds limit " + maxBars + " (缩短区间或用更粗的 K 线周期)");
        }
    }

    public BacktestTask getOwned(long taskId, long userId) {
        BacktestTask task = taskMapper.findById(taskId);
        if (task == null) {
            throw new BacktestTaskNotFoundException(taskId);
        }
        return OwnershipCheck.requireOwned(task, task.getUserId(), userId, "backtest_task");
    }

    /**
     * Worker klines 请求守卫：请求参数必须与任务快照一致，区间必须落在任务快照区间内。
     *
     * <p>此前 klines 端点直接信任 query 参数——持任意合法 BACKTEST token 的 worker 可拉取与自身任务
     * 无关的任意 symbol/interval/区间（含超任务区间的历史），把任务 token 当通配行情代理用。此方法把
     * （exchange + symbol + interval + marketType + [start, end)）钉死在提交时冻结的任务快照上。
     *
     * <p>userId 取 {@link SecurityUtils#currentUserId()}（WorkerTokenFilter 注入 token 归属用户，
     * 与 {@link #reportProgress} 同模式），DB 层 getOwned 双重校验 ownership。
     *
     * @throws BacktestTaskNotFoundException 任务不存在（404/7301）
     * @throws com.kwikquant.shared.infra.OwnershipViolationException 任务不属于该用户（403/3002）
     * @throws ResourceStateConflictException 任务非 RUNNING（409/4009）
     * @throws IllegalArgumentException 参数与任务快照不符或区间越界（400/3001）
     */
    public void requireKlineRequestWithinTask(
            long taskId,
            Exchange exchange,
            MarketType marketType,
            String symbol,
            Interval interval,
            Instant start,
            Instant end) {
        BacktestTask task = getOwned(taskId, SecurityUtils.currentUserId());
        if (task.getStatus() != BacktestTaskStatus.RUNNING) {
            throw new ResourceStateConflictException(
                    "backtest_task " + taskId + " is " + task.getStatus() + ", klines only served while RUNNING");
        }
        // 维度逐一与任务快照精确比对(worker 的 RunRequest 参数本就来自任务快照,逐字一致)
        requireFieldMatch("exchange", task.getExchange(), exchange == null ? null : exchange.name());
        requireFieldMatch("symbol", task.getSymbol(), symbol);
        requireFieldMatch("interval", task.getIntervalValue(), interval == null ? null : interval.ccxtValue());
        requireFieldMatch("marketType", task.getMarketType(), marketType == null ? null : marketType.name());
        // 区间：[start, end) 必须 ⊆ 任务快照 [startTime, endTime)
        if (start == null || end == null || !start.isBefore(end)) {
            throw new IllegalArgumentException("klines start must be before end");
        }
        if (start.isBefore(task.getStartTime()) || end.isAfter(task.getEndTime())) {
            throw new IllegalArgumentException("klines range ["
                    + start + ", " + end + ") exceeds task snapshot ["
                    + task.getStartTime() + ", " + task.getEndTime() + ")");
        }
    }

    private static void requireFieldMatch(String field, String snapshot, String requested) {
        if (!Objects.equals(snapshot, requested)) {
            throw new IllegalArgumentException(
                    "klines " + field + " mismatch: task snapshot is " + snapshot + ", requested " + requested);
        }
    }

    public List<BacktestTask> listByStrategy(long strategyId, long userId) {
        crudService.getOwned(strategyId, userId);
        return taskMapper.findByStrategyId(strategyId);
    }

    /**
     * 当前用户全部回测任务(全列表路径,供回测 tab 列表 rail)。
     *
     * <p>组装 {@link BacktestTaskSummary}:totalReturn 走 {@link ReportService#findTotalReturnsByIds}
     * 批量取(COMPLETED task 才有 reportId,RUNNING/PENDING 的 totalReturn 为 null),strategyName 走
     * {@link StrategyCrudService#listByUser} 批量取(一次查所有策略建 id→name 映射,避免逐个 getOwned N 次)。
     *
     * <p>返 application 层 summary(非 interfaces DTO),避免 service 依赖 interfaces 违反分层。
     */
    public List<BacktestTaskSummary> listByUser(long userId) {
        List<BacktestTask> tasks = taskMapper.findByUserId(userId);
        List<Long> reportIds = tasks.stream()
                .map(BacktestTask::getReportId)
                .filter(Objects::nonNull)
                .toList();
        Map<Long, BigDecimal> totalReturns = reportService.findTotalReturnsByIds(reportIds, userId);
        Map<Long, String> strategyNames = crudService.listByUser(userId).stream()
                .collect(Collectors.toMap(StrategyDefinition::getId, StrategyDefinition::getName));
        return tasks.stream()
                .map(t -> new BacktestTaskSummary(
                        t.getId(),
                        t.getStrategyId(),
                        t.getStrategyCodeId(),
                        t.getStatus(),
                        t.getSymbol(),
                        t.getExchange(),
                        t.getIntervalValue(),
                        t.getStartTime(),
                        t.getEndTime(),
                        t.getParameters(),
                        t.getResult(),
                        t.getReportId(),
                        t.getErrorMessage(),
                        t.getProcessedBars(),
                        t.getTotalBars(),
                        t.getCreatedAt(),
                        t.getUpdatedAt(),
                        t.getReportId() != null ? totalReturns.get(t.getReportId()) : null,
                        strategyNames.get(t.getStrategyId())))
                .toList();
    }

    /**
     * 逐 bar 进度上报(Worker 通道,X-Worker-Token 鉴权后 WorkerTokenFilter 注入 userId)。
     *
     * <p>写 {@code processed_bars/total_bars} + 发 WS RUNNING 增量(前端进度条)。{@code updateProgress}
     * 带 {@code status = 'RUNNING'} 守卫:task 已终态(COMPLETED/FAILED)时返 0,跳过 WS,防误推进度
     * 给已结束的任务。userId 取 SecurityContext(filter 注入 workerUserId),DB 双重校验 ownership。
     */
    public void reportProgress(long taskId, int processedBars, int totalBars) {
        long userId = SecurityUtils.currentUserId();
        int affected = taskMapper.updateProgress(taskId, userId, processedBars, totalBars);
        if (affected == 0) {
            // task 非 RUNNING 或非本人 → 静默跳过(不报错,worker 不消费响应,避免已终态误推 RUNNING)
            return;
        }
        ws.convertAndSend("/topic/backtests/" + userId, (Object) Map.of(
                "taskId", taskId,
                "status", BacktestTaskStatus.RUNNING.name(),
                "processedBars", processedBars,
                "totalBars", totalBars));
    }
}
