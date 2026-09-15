package com.kwikquant.strategy.application;

import com.kwikquant.market.application.FundingCoverageGuard;
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
import com.kwikquant.strategy.domain.BacktestWorkerUnavailableException;
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
import java.util.Optional;
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

    /** 组合回测标的数上限(防超大标的集拖垮逐标的取数/撮合;截面轮动典型 3~20 个)。 */
    static final int MAX_PORTFOLIO_SYMBOLS = 20;

    private final BacktestTaskMapper taskMapper;
    private final Optional<BacktestWorkerHealthChecker> workerHealthChecker;
    private final StrategyCrudService crudService;
    private final StrategyCodeService codeService;
    private final BacktestExecutionGateway executionGateway;
    private final SimpMessagingTemplate ws;
    private final ReportService reportService;
    private final BacktestQuotaGuard quotaGuard;
    private final FundingCoverageGuard fundingCoverageGuard;
    private final long maxBars;

    public BacktestTaskService(
            BacktestTaskMapper taskMapper,
            StrategyCrudService crudService,
            StrategyCodeService codeService,
            BacktestExecutionGateway executionGateway,
            SimpMessagingTemplate ws,
            ReportService reportService,
            BacktestQuotaGuard quotaGuard,
            FundingCoverageGuard fundingCoverageGuard,
            @Value("${kwikquant.backtest.max-bars:100000}") long maxBars,
            Optional<BacktestWorkerHealthChecker> workerHealthChecker) {
        this.taskMapper = taskMapper;
        this.crudService = crudService;
        this.codeService = codeService;
        this.executionGateway = executionGateway;
        this.ws = ws;
        this.reportService = reportService;
        this.quotaGuard = quotaGuard;
        this.fundingCoverageGuard = fundingCoverageGuard;
        this.maxBars = maxBars;
        this.workerHealthChecker = workerHealthChecker;
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
        return submit(strategyId, userId, symbol, exchange, intervalValue, startTime, endTime, parameters, false);
    }

    /**
     * 提交单标的回测(全参)。{@code allowFundingProxy} 仅 PERP 有意义:资金费序列缺期时显式
     * 允许用 Binance 同期次值跨所代理补写(source=PROXY_BINANCE,报告标注基差风险);
     * 默认 false = fail-closed 拒(见 {@link com.kwikquant.market.application.FundingCoverageGuard})。
     */
    public BacktestTask submit(
            long strategyId,
            long userId,
            String symbol,
            String exchange,
            String intervalValue,
            Instant startTime,
            Instant endTime,
            String parameters,
            boolean allowFundingProxy) {
        return doSubmit(
                strategyId,
                userId,
                symbol,
                null,
                exchange,
                intervalValue,
                startTime,
                endTime,
                parameters,
                allowFundingProxy);
    }

    /**
     * 提交组合(多标的)回测任务。策略在一次回测里同时消费 {@code symbols} 全部标的、在共享现金池
     * 里跨标的下单(策略契约 {@code on_bars(ctx)},见 Python worker)。
     *
     * <p>复用与单标的完全相同的任务状态机、并发配额与失败分类;差异仅在任务快照样张(
     * {@code backtest_tasks.symbols})与 worker 下发/取数按标的集合展开。
     *
     * @param symbols 标的列表(≥2、无重复、各自 {@code BASE/QUOTE} 规范形)
     */
    public BacktestTask submitPortfolio(
            long strategyId,
            long userId,
            List<String> symbols,
            String exchange,
            String intervalValue,
            Instant startTime,
            Instant endTime,
            String parameters,
            boolean allowFundingProxy) {
        validatePortfolioSymbols(symbols);
        return doSubmit(
                strategyId,
                userId,
                null,
                symbols,
                exchange,
                intervalValue,
                startTime,
                endTime,
                parameters,
                allowFundingProxy);
    }

    private BacktestTask doSubmit(
            long strategyId,
            long userId,
            String symbol,
            List<String> symbols,
            String exchange,
            String intervalValue,
            Instant startTime,
            Instant endTime,
            String parameters,
            boolean allowFundingProxy) {
        boolean portfolio = symbols != null && !symbols.isEmpty();
        // worker 环境自检失败前置拒绝(7305),避免用户等执行超时才看到 spawn failed;docker profile 无 checker 跳过
        workerHealthChecker.ifPresent(c -> {
            if (!c.isAvailable()) {
                throw new BacktestWorkerUnavailableException(c.detail());
            }
        });
        StrategyDefinition strategy = crudService.getOwned(strategyId, userId);
        StrategyCode code = codeService.getPublishedCode(strategyId);
        if (code == null) {
            throw new NoPublishedStrategyCodeException(strategyId);
        }
        // symbol 列保持非空:单标的任务存该标的(覆盖值 → 策略默认值);组合任务存逗号拼接的
        // 多标的列表(与 backtest_reports.symbol 口径一致)。是否组合以 symbols 数组判别。
        String resolvedSymbol =
                portfolio ? String.join(",", symbols) : (symbol != null ? symbol : strategy.getSymbol());
        String resolvedExchange = exchange != null ? exchange : strategy.getExchange();
        String resolvedInterval = intervalValue != null ? intervalValue : strategy.getIntervalValue();
        // 轻量校验:exchange 必须是真实枚举(非 PAPER,模拟盘 exchange='OKX' 非 PAPER)、interval 合法、
        // start<end、bar 数上限;单标的还要求 symbol 非空。非法抛 IllegalArgumentException
        // (@RestControllerAdvice 转 3001 VALIDATION_FAILED / 400)。
        validateBacktestParams(resolvedSymbol, resolvedExchange, resolvedInterval, startTime, endTime, portfolio);
        // marketType 快照:提交时冻结策略市场类型,V54 落 backtest_tasks.market_type。排队期间策略被改
        // 不影响执行语义(worker 与 klines 端点均以任务快照为准)。
        String marketTypeSnapshot = snapshotMarketType(strategy);
        if ("PERP".equals(marketTypeSnapshot)) {
            // 资金费序列预检 fail-closed(spec §7/§10.8):缺期即拒并列出出路;allowFundingProxy 显式
            // 放行 Binance 跨所代理补写(提交时补,执行期复查无需再知 flag)。组合 PERP 逐标的独立预检
            // (perp-backtest-spec §10:组合 PERP 已支持,任一标的缺期即拒)。
            List<String> coverageSymbols = portfolio ? symbols : List.of(resolvedSymbol);
            Instant now = Instant.now();
            for (String cover : coverageSymbols) {
                fundingCoverageGuard.ensureCoverage(
                        Exchange.valueOf(resolvedExchange), cover, startTime, endTime, allowFundingProxy, now);
            }
        }
        BacktestTask task = BacktestTask.create(
                strategyId,
                userId,
                code.getId(),
                resolvedSymbol,
                portfolio ? List.copyOf(symbols) : null,
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

    /** 组合标的列表校验:非空、≥2、无重复、各自 {@code BASE/QUOTE} 规范形。超限/非法抛 400/3001。 */
    private static void validatePortfolioSymbols(List<String> symbols) {
        if (symbols == null || symbols.isEmpty()) {
            throw new IllegalArgumentException("portfolio backtest symbols must not be empty");
        }
        if (symbols.size() < 2) {
            throw new IllegalArgumentException("portfolio backtest requires at least 2 symbols");
        }
        if (symbols.size() > MAX_PORTFOLIO_SYMBOLS) {
            throw new IllegalArgumentException("portfolio backtest too many symbols: " + symbols.size()
                    + " exceeds limit " + MAX_PORTFOLIO_SYMBOLS);
        }
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String s : symbols) {
            if (s == null || s.isBlank()) {
                throw new IllegalArgumentException("portfolio backtest symbol must not be blank");
            }
            // canonical 形校验(与单标的取数/报告导入口径一致):恰好一个 '/'、BASE/QUOTE 两段非空、
            // 无首尾空白、全大写。拒掉 "BTC/USDT/FOO"、" BTC/USDT "、"btc/usdt" 等,入口即拦而非跑完才失败。
            if (!s.equals(s.trim())) {
                throw new IllegalArgumentException("portfolio backtest symbol has surrounding whitespace: " + s);
            }
            int slash = s.indexOf('/');
            if (slash <= 0 || slash != s.lastIndexOf('/') || slash >= s.length() - 1) {
                throw new IllegalArgumentException("portfolio backtest symbol invalid (expect BASE/QUOTE): " + s);
            }
            if (!s.equals(s.toUpperCase(java.util.Locale.ROOT))) {
                throw new IllegalArgumentException("portfolio backtest symbol must be canonical uppercase: " + s);
            }
            if (!seen.add(s)) {
                throw new IllegalArgumentException("portfolio backtest symbols contain duplicate: " + s);
            }
        }
    }

    /** marketType 快照(空兜底 SPOT;PERP 已支持——提交时过组合拒绝与资金费预检,见 doSubmit)。 */
    private static String snapshotMarketType(StrategyDefinition strategy) {
        String mt = strategy.getMarketType();
        return (mt == null || mt.isBlank()) ? "SPOT" : mt.toUpperCase();
    }

    private void validateBacktestParams(
            String symbol,
            String exchange,
            String intervalValue,
            Instant startTime,
            Instant endTime,
            boolean portfolio) {
        // 组合任务标的集合已在 validatePortfolioSymbols 校验,此处无单一 symbol
        if (!portfolio && (symbol == null || symbol.isBlank())) {
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
        // interval 枚举校验(入口拒掉非法值,避免进 DB 后到 worker 拉数据才失败)
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
     * <p>持合法 BACKTEST token 不意味着可拉任意行情:本方法把（exchange + symbol + interval +
     * marketType + [start, end)）钉死在提交时冻结的任务快照上,防止任务 token 被当通配行情代理
     * 拉取与自身任务无关的 symbol/interval/区间（含超任务区间的历史）。
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
        if (task.isPortfolio()) {
            // 组合任务:请求的 symbol 必须属于任务快照的标的集合(逐标的取数,集合外一律拒)
            if (task.getSymbols() == null || !task.getSymbols().contains(symbol)) {
                throw new IllegalArgumentException("klines symbol mismatch: task snapshot symbols are "
                        + task.getSymbols() + ", requested " + symbol);
            }
        } else {
            requireFieldMatch("symbol", task.getSymbol(), symbol);
        }
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

    /**
     * Worker funding-rates 请求守卫(PERP 资金费回放,docs/perp-backtest-spec.md §5):
     * 与 {@link #requireKlineRequestWithinTask} 同模式——维度钉死任务快照,无 interval 维度
     * (资金费期次周期由序列行自带),区间 end 允许 24h 前瞻缓冲(末根 bar 的期次可落在任务
     * end 之后,左开右闭归属,见 {@link com.kwikquant.market.application.FundingCoverageGuard#fundingQueryEnd})。
     *
     * @throws BacktestTaskNotFoundException 任务不存在(404/7301)
     * @throws com.kwikquant.shared.infra.OwnershipViolationException 任务不属于该用户(403/3002)
     * @throws ResourceStateConflictException 任务非 RUNNING(409/4009)
     * @throws IllegalArgumentException 参数与任务快照不符或区间越界(400/3001)
     */
    public void requireFundingRequestWithinTask(
            long taskId, Exchange exchange, MarketType marketType, String symbol, Instant start, Instant end) {
        BacktestTask task = getOwned(taskId, SecurityUtils.currentUserId());
        if (task.getStatus() != BacktestTaskStatus.RUNNING) {
            throw new ResourceStateConflictException("backtest_task " + taskId + " is " + task.getStatus()
                    + ", funding-rates only served while RUNNING");
        }
        requireFieldMatch("exchange", task.getExchange(), exchange == null ? null : exchange.name());
        if (task.isPortfolio()) {
            // 组合 PERP:worker 逐标的拉资金费,请求 symbol 须属任务快照标的集合(与 klines 守卫同口径)
            if (task.getSymbols() == null || !task.getSymbols().contains(symbol)) {
                throw new IllegalArgumentException("funding-rates symbol mismatch: task snapshot symbols are "
                        + task.getSymbols() + ", requested " + symbol);
            }
        } else {
            requireFieldMatch("symbol", task.getSymbol(), symbol);
        }
        requireFieldMatch("marketType", task.getMarketType(), marketType == null ? null : marketType.name());
        if (start == null || end == null || !start.isBefore(end)) {
            throw new IllegalArgumentException("funding-rates start must be before end");
        }
        Instant allowedEnd = com.kwikquant.market.application.FundingCoverageGuard.fundingQueryEnd(task.getEndTime());
        if (start.isBefore(task.getStartTime()) || end.isAfter(allowedEnd)) {
            throw new IllegalArgumentException("funding-rates range ["
                    + start + ", " + end + ") exceeds task snapshot ["
                    + task.getStartTime() + ", " + allowedEnd + ")");
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
                        t.getSymbols(),
                        t.getMarketType(),
                        t.getExchange(),
                        t.getIntervalValue(),
                        t.getStartTime(),
                        t.getEndTime(),
                        t.getParameters(),
                        t.getResult(),
                        t.getReportId(),
                        t.getErrorMessage(),
                        t.getFailureCategory(),
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
        // timestamp 与 ws-contract BacktestEvent 契约对齐(必填,ISO-8601 UTC;COMPLETED/FAILED 同)
        ws.convertAndSend("/topic/backtests/" + userId, (Object) Map.of(
                "taskId", taskId,
                "status", BacktestTaskStatus.RUNNING.name(),
                "processedBars", processedBars,
                "totalBars", totalBars,
                "timestamp", Instant.now().toString()));
    }
}
