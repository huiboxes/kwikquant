package com.kwikquant.report.domain;

import java.math.BigDecimal;

public record PerformanceMetrics(
        BigDecimal totalReturn,
        BigDecimal sharpeRatio,
        BigDecimal maxDrawdown,
        BigDecimal winRate,
        BigDecimal profitFactor,
        int totalTrades,
        long avgTradeDurationSeconds) {}
