package com.kwikquant.strategy.domain;

import java.time.Instant;

/**
 * 回测任务实体。记录回测执行的参数、状态和结果。
 *
 * <p>状态推进通过 {@link #transitionTo(BacktestTaskStatus)} 进行。
 */
public class BacktestTask {

    private Long id;
    private long strategyId;
    private long userId;
    private long strategyCodeId;
    private BacktestTaskStatus status;
    /**
     * 回测交易对快照。单标的任务存该标的;组合(多标的)任务存逗号拼接的多标的列表(与
     * {@code backtest_reports.symbol} 口径一致)。是否组合以 {@link #symbols} 是否为 {@code null} 判别。
     */
    private String symbol;
    /**
     * 组合(多标的)回测的标的列表快照(单标的任务为 {@code null})。组合任务同时保留逗号拼接的
     * {@link #symbol} 供展示;结构化标的集合以本字段为准(Worker 据此逐标的拉数据并在共享现金池撮合)。
     */
    private java.util.List<String> symbols;

    private String exchange;
    /** 市场类型快照(提交时从策略冻结,SPOT/PERP)。Worker 拉数据与 klines 端点校验以此为准。 */
    private String marketType;

    private String intervalValue;
    private Instant startTime;
    private Instant endTime;
    private String parameters;
    private String result;
    private String errorMessage;
    private Instant createdAt;
    private Instant updatedAt;
    /** 回测报告 ID（COMPLETED 时回填，task→report 导航桥梁）。 */
    private Long reportId;
    /** 已处理 bar 数（RUNNING 期间由 worker 逐 bar 上报，节流 ~200 bar/次；终态不重置）。 */
    private Integer processedBars;
    /** 总 bar 数（worker 拉完 klines 后上报，进度分母）。 */
    private Integer totalBars;
    /** 失败分类（FAILED 时有值，见 {@link BacktestFailureCategory}；历史记录 nullable）。 */
    private String failureCategory;

    public BacktestTask() {}

    /**
     * 工厂方法：创建 PENDING 状态的回测任务。
     *
     * @param strategyId 策略 ID
     * @param userId 用户 ID
     * @param strategyCodeId 策略代码版本 ID
     * @param symbol 交易对
     * @param exchange 交易所
     * @param marketType 市场类型快照(SUBMIT 时从策略冻结,SPOT/PERP)
     * @param intervalValue K线周期
     * @param startTime 回测开始时间
     * @param endTime 回测结束时间
     * @param parameters 回测参数 JSON
     * @return PENDING 状态的新回测任务
     */
    public static BacktestTask create(
            long strategyId,
            long userId,
            long strategyCodeId,
            String symbol,
            String exchange,
            String marketType,
            String intervalValue,
            Instant startTime,
            Instant endTime,
            String parameters) {
        return create(
                strategyId,
                userId,
                strategyCodeId,
                symbol,
                null,
                exchange,
                marketType,
                intervalValue,
                startTime,
                endTime,
                parameters);
    }

    /**
     * 工厂方法：创建 PENDING 状态的回测任务（组合/多标的）。
     *
     * <p>{@code symbol} 恒非空：单标的任务即该标的，组合任务为逗号拼接的多标的列表（供展示）。
     * 是否组合以 {@code symbols} 是否为 {@code null} 判别：单标的传 {@code null}，组合传标的列表。
     *
     * @param symbols 组合回测标的列表（单标的任务传 null）
     */
    public static BacktestTask create(
            long strategyId,
            long userId,
            long strategyCodeId,
            String symbol,
            java.util.List<String> symbols,
            String exchange,
            String marketType,
            String intervalValue,
            Instant startTime,
            Instant endTime,
            String parameters) {
        BacktestTask t = new BacktestTask();
        t.strategyId = strategyId;
        t.userId = userId;
        t.strategyCodeId = strategyCodeId;
        t.status = BacktestTaskStatus.PENDING;
        t.symbol = symbol;
        t.symbols = symbols;
        t.exchange = exchange;
        t.marketType = marketType;
        t.intervalValue = intervalValue;
        t.startTime = startTime;
        t.endTime = endTime;
        t.parameters = parameters != null ? parameters : "{}";
        return t;
    }

    /** 是否组合(多标的)回测任务。 */
    public boolean isPortfolio() {
        return symbols != null && !symbols.isEmpty();
    }

    /**
     * 状态推进。违反状态机抛异常。
     *
     * @param target 目标状态
     * @throws IllegalBacktestTaskStateTransitionException 非法状态转换时抛出
     */
    public void transitionTo(BacktestTaskStatus target) {
        if (status == null || !status.canTransitionTo(target)) {
            throw new IllegalBacktestTaskStateTransitionException(status, target);
        }
        this.status = target;
    }

    // ---------- getters / setters ----------

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public long getStrategyId() {
        return strategyId;
    }

    public void setStrategyId(long strategyId) {
        this.strategyId = strategyId;
    }

    public long getUserId() {
        return userId;
    }

    public void setUserId(long userId) {
        this.userId = userId;
    }

    public long getStrategyCodeId() {
        return strategyCodeId;
    }

    public void setStrategyCodeId(long strategyCodeId) {
        this.strategyCodeId = strategyCodeId;
    }

    public BacktestTaskStatus getStatus() {
        return status;
    }

    public void setStatus(BacktestTaskStatus status) {
        this.status = status;
    }

    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }

    public java.util.List<String> getSymbols() {
        return symbols;
    }

    public void setSymbols(java.util.List<String> symbols) {
        this.symbols = symbols;
    }

    public String getExchange() {
        return exchange;
    }

    public void setExchange(String exchange) {
        this.exchange = exchange;
    }

    public String getMarketType() {
        return marketType;
    }

    public void setMarketType(String marketType) {
        this.marketType = marketType;
    }

    public String getIntervalValue() {
        return intervalValue;
    }

    public void setIntervalValue(String intervalValue) {
        this.intervalValue = intervalValue;
    }

    public Instant getStartTime() {
        return startTime;
    }

    public void setStartTime(Instant startTime) {
        this.startTime = startTime;
    }

    public Instant getEndTime() {
        return endTime;
    }

    public void setEndTime(Instant endTime) {
        this.endTime = endTime;
    }

    public String getParameters() {
        return parameters;
    }

    public void setParameters(String parameters) {
        this.parameters = parameters;
    }

    public String getResult() {
        return result;
    }

    public void setResult(String result) {
        this.result = result;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Long getReportId() {
        return reportId;
    }

    public void setReportId(Long reportId) {
        this.reportId = reportId;
    }

    public Integer getProcessedBars() {
        return processedBars;
    }

    public void setProcessedBars(Integer processedBars) {
        this.processedBars = processedBars;
    }

    public Integer getTotalBars() {
        return totalBars;
    }

    public void setTotalBars(Integer totalBars) {
        this.totalBars = totalBars;
    }

    public String getFailureCategory() {
        return failureCategory;
    }

    public void setFailureCategory(String failureCategory) {
        this.failureCategory = failureCategory;
    }
}
