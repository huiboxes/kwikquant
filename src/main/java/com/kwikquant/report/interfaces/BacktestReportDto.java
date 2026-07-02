package com.kwikquant.report.interfaces;

import java.math.BigDecimal;
import java.time.Instant;

public record BacktestReportDto(
        long id,
        String name,
        String symbol,
        String timeframe,
        Instant periodStart,
        Instant periodEnd,
        BigDecimal totalReturn,
        BigDecimal sharpeRatio,
        BigDecimal maxDrawdown,
        BigDecimal winRate,
        BigDecimal profitFactor,
        int totalTrades,
        String source,
        Instant createdAt) {}
