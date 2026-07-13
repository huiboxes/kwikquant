package com.kwikquant.report.interfaces;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record BacktestReportDetailDto(
        @Schema(description = "报告 ID", example = "42") long id,
        @Schema(description = "报告名称", example = "BTC/USDT 网格回测") String name,
        @Schema(description = "回测标的 canonical symbol", example = "BTC/USDT") String symbol,
        @Schema(description = "时间周期", example = "1h") String timeframe,
        @Schema(description = "回测区间起始", example = "2026-06-01T00:00:00Z") Instant periodStart,
        @Schema(description = "回测区间结束", example = "2026-07-01T00:00:00Z") Instant periodEnd,
        @Schema(description = "回测参数键值对（策略入参快照）") String params,
        @Schema(description = "核心指标") MetricsDto metrics,
        @Schema(description = "交易明细列表") List<TradeRecordDto> trades,
        @Schema(description = "权益曲线点列表") List<EquityPointDto> equityCurve,
        @Schema(description = "来源标记", example = "BACKTEST") String source,
        @Schema(description = "创建时间", example = "2026-07-04T12:00:00Z") Instant createdAt,
        @Schema(description = "最后更新时间", example = "2026-07-04T12:00:00Z") Instant updatedAt) {

    public record MetricsDto(
            @Schema(description = "总收益率（小数）", example = "0.1532") BigDecimal totalReturn,
            @Schema(description = "夏普比率", example = "1.85") BigDecimal sharpeRatio,
            @Schema(description = "最大回撤（小数，负值）", example = "-0.0842") BigDecimal maxDrawdown,
            @Schema(description = "胜率（0-1）", example = "0.62") BigDecimal winRate,
            @Schema(description = "盈亏比", example = "2.10") BigDecimal profitFactor,
            @Schema(description = "总交易笔数", example = "128") int totalTrades,
            @Schema(description = "平均持仓时长（秒）", example = "3600") long avgTradeDurationSeconds) {}

    public record TradeRecordDto(
            @Schema(description = "交易记录 ID", example = "1024") long id,
            @Schema(description = "成交时间", example = "2026-06-15T08:30:00Z") Instant time,
            @Schema(description = "方向（枚举: buy | sell）", example = "buy") String side,
            @Schema(description = "成交价格（金额，精度 8 位）", example = "42150.50") BigDecimal price,
            @Schema(description = "成交数量（精度 8 位）", example = "0.0025") BigDecimal amount,
            @Schema(description = "手续费（精度 8 位）", example = "0.0052") BigDecimal fee,
            @Schema(description = "该笔交易的已实现盈亏（精度 8 位），首单或无配对时为 null", example = "-0.0052") BigDecimal realizedPnl,
            @Schema(description = "该笔交易后的累计权益（精度 8 位），无数据时为 null", example = "10032.15") BigDecimal equity) {}

    public record EquityPointDto(
            @Schema(description = "时间点", example = "2026-06-15T08:30:00Z") Instant time,
            @Schema(description = "权益（USDT 估值，精度 2 位）", example = "10532.18") BigDecimal equity) {}
}
