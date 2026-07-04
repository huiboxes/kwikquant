package com.kwikquant.report.interfaces;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;

public record BacktestReportDto(
        @Schema(description = "报告 ID", example = "42") long id,
        @Schema(description = "报告名称", example = "BTC/USDT 网格回测") String name,
        @Schema(description = "回测标的 canonical symbol", example = "BTC/USDT") String symbol,
        @Schema(description = "时间周期", example = "1h") String timeframe,
        @Schema(description = "回测区间起始", example = "2026-06-01T00:00:00Z") Instant periodStart,
        @Schema(description = "回测区间结束", example = "2026-07-01T00:00:00Z") Instant periodEnd,
        @Schema(description = "总收益率（小数，0.15 表示 15%）", example = "0.1532") BigDecimal totalReturn,
        @Schema(description = "夏普比率", example = "1.85") BigDecimal sharpeRatio,
        @Schema(description = "最大回撤（小数，负值）", example = "-0.0842") BigDecimal maxDrawdown,
        @Schema(description = "胜率（0-1）", example = "0.62") BigDecimal winRate,
        @Schema(description = "盈亏比", example = "2.10") BigDecimal profitFactor,
        @Schema(description = "总交易笔数", example = "128") int totalTrades,
        @Schema(description = "来源标记（USER/IMPORT/BACKTEST 等，由调用方传入）", example = "BACKTEST") String source,
        @Schema(description = "创建时间", example = "2026-07-04T12:00:00Z") Instant createdAt) {}
