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
    private String symbol;
    private String exchange;
    private String intervalValue;
    private Instant startTime;
    private Instant endTime;
    private String parameters;
    private String result;
    private String errorMessage;
    private Instant createdAt;
    private Instant updatedAt;
    /** 回测报告 ID（COMPLETED 时回填，task→report 导航桥梁，契约改动 B）。 */
    private Long reportId;

    public BacktestTask() {}

    /**
     * 工厂方法：创建 PENDING 状态的回测任务。
     *
     * @param strategyId 策略 ID
     * @param userId 用户 ID
     * @param strategyCodeId 策略代码版本 ID
     * @param symbol 交易对
     * @param exchange 交易所
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
        t.exchange = exchange;
        t.intervalValue = intervalValue;
        t.startTime = startTime;
        t.endTime = endTime;
        t.parameters = parameters != null ? parameters : "{}";
        return t;
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

    public String getExchange() {
        return exchange;
    }

    public void setExchange(String exchange) {
        this.exchange = exchange;
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
}
