package com.kwikquant.report.interfaces;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record BacktestReportDetailDto(
        long id,
        String name,
        String symbol,
        String timeframe,
        Instant periodStart,
        Instant periodEnd,
        String params,
        MetricsDto metrics,
        List<TradeRecordDto> trades,
        List<EquityPointDto> equityCurve,
        String source,
        Instant createdAt,
        Instant updatedAt) {

    public record MetricsDto(
            BigDecimal totalReturn,
            BigDecimal sharpeRatio,
            BigDecimal maxDrawdown,
            BigDecimal winRate,
            BigDecimal profitFactor,
            int totalTrades,
            long avgTradeDurationSeconds) {}

    public record TradeRecordDto(
            long id, Instant time, String side, BigDecimal price, BigDecimal amount, BigDecimal fee) {}

    public record EquityPointDto(Instant time, BigDecimal equity) {}
}
