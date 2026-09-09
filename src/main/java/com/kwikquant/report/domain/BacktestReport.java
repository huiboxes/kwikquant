package com.kwikquant.report.domain;

import java.math.BigDecimal;
import java.time.Instant;

public class BacktestReport {
    private long id;
    private long userId;
    private String name;
    private String params;
    private String symbol;
    /** 组合(多标的)回测的标的列表(单标的报告为 {@code null})。JSONB,存储为字符串。 */
    private String symbols;
    /** 分标的终仓快照 [{symbol,qty,avgPrice}]。JSONB,存储为字符串。组合报告，单标的为 {@code null}。 */
    private String finalPositions;

    /** 报告市场类型(SPOT|PERP)。V60,存量默认 SPOT;决定指标配对路径(perp-backtest-spec §8.2)。 */
    private String marketType;
    /** PERP 强平近似模型声明(仅 PERP 非空,如 BAR_EXTREME_APPROX,perp-backtest-spec §4.2)。 */
    private String liquidationModel;

    private String timeframe;
    private Instant periodStart;
    private Instant periodEnd;
    private String equityCurve;
    private BigDecimal totalReturn;
    private BigDecimal sharpeRatio;
    private BigDecimal maxDrawdown;
    private BigDecimal winRate;
    private BigDecimal profitFactor;
    private int totalTrades;
    private long avgTradeDurationSeconds;
    private String source;
    private Instant createdAt;
    private Instant updatedAt;

    public BacktestReport() {}

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public long getUserId() {
        return userId;
    }

    public void setUserId(long userId) {
        this.userId = userId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getParams() {
        return params;
    }

    public void setParams(String params) {
        this.params = params;
    }

    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }

    public String getSymbols() {
        return symbols;
    }

    public void setSymbols(String symbols) {
        this.symbols = symbols;
    }

    public String getFinalPositions() {
        return finalPositions;
    }

    public void setFinalPositions(String finalPositions) {
        this.finalPositions = finalPositions;
    }

    public String getMarketType() {
        return marketType;
    }

    public void setMarketType(String marketType) {
        this.marketType = marketType;
    }

    public String getLiquidationModel() {
        return liquidationModel;
    }

    public void setLiquidationModel(String liquidationModel) {
        this.liquidationModel = liquidationModel;
    }

    public String getTimeframe() {
        return timeframe;
    }

    public void setTimeframe(String timeframe) {
        this.timeframe = timeframe;
    }

    public Instant getPeriodStart() {
        return periodStart;
    }

    public void setPeriodStart(Instant periodStart) {
        this.periodStart = periodStart;
    }

    public Instant getPeriodEnd() {
        return periodEnd;
    }

    public void setPeriodEnd(Instant periodEnd) {
        this.periodEnd = periodEnd;
    }

    public String getEquityCurve() {
        return equityCurve;
    }

    public void setEquityCurve(String equityCurve) {
        this.equityCurve = equityCurve;
    }

    public BigDecimal getTotalReturn() {
        return totalReturn;
    }

    public void setTotalReturn(BigDecimal totalReturn) {
        this.totalReturn = totalReturn;
    }

    public BigDecimal getSharpeRatio() {
        return sharpeRatio;
    }

    public void setSharpeRatio(BigDecimal sharpeRatio) {
        this.sharpeRatio = sharpeRatio;
    }

    public BigDecimal getMaxDrawdown() {
        return maxDrawdown;
    }

    public void setMaxDrawdown(BigDecimal maxDrawdown) {
        this.maxDrawdown = maxDrawdown;
    }

    public BigDecimal getWinRate() {
        return winRate;
    }

    public void setWinRate(BigDecimal winRate) {
        this.winRate = winRate;
    }

    public BigDecimal getProfitFactor() {
        return profitFactor;
    }

    public void setProfitFactor(BigDecimal profitFactor) {
        this.profitFactor = profitFactor;
    }

    public int getTotalTrades() {
        return totalTrades;
    }

    public void setTotalTrades(int totalTrades) {
        this.totalTrades = totalTrades;
    }

    public long getAvgTradeDurationSeconds() {
        return avgTradeDurationSeconds;
    }

    public void setAvgTradeDurationSeconds(long avgTradeDurationSeconds) {
        this.avgTradeDurationSeconds = avgTradeDurationSeconds;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
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
}
