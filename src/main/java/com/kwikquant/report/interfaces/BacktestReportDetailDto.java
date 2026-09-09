package com.kwikquant.report.interfaces;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record BacktestReportDetailDto(
        @Schema(description = "报告 ID", example = "42") long id,
        @Schema(description = "报告名称", example = "BTC/USDT 网格回测") String name,
        @Schema(description = "回测标的 canonical symbol(组合回测为逗号拼接的多标的)", example = "BTC/USDT") String symbol,
        @Schema(description = "组合回测标的列表(单标的报告为空列表)", example = "[\"BTC/USDT\",\"ETH/USDT\"]") List<String> symbols,
        @Schema(description = "市场类型（SPOT | PERP，存量报告为 SPOT）", example = "PERP") String marketType,
        @Schema(
                        description = "PERP 强平近似模型声明（仅 PERP 报告非空；BAR_EXTREME_APPROX = bar 极值近似，"
                                + "存在保守偏差，见 perp-backtest-spec §4.2）",
                        example = "BAR_EXTREME_APPROX",
                        nullable = true)
                String liquidationModel,
        @Schema(description = "时间周期", example = "1h") String timeframe,
        @Schema(description = "回测区间起始", example = "2026-06-01T00:00:00Z") Instant periodStart,
        @Schema(description = "回测区间结束", example = "2026-07-01T00:00:00Z") Instant periodEnd,
        @Schema(description = "回测参数键值对（策略入参快照）") String params,
        @Schema(description = "核心指标") MetricsDto metrics,
        @Schema(description = "交易明细列表") List<TradeRecordDto> trades,
        @Schema(description = "权益曲线点列表") List<EquityPointDto> equityCurve,
        @Schema(description = "组合回测分标的终仓快照(单标的报告为空列表)") List<FinalPositionDto> positions,
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
            @Schema(description = "成交标的(组合回测逐笔标记;单标的报告为 null)", example = "BTC/USDT", nullable = true) String symbol,
            @Schema(description = "方向（枚举: buy | sell；PERP 为派生量，buy≠开仓，语义看 positionEffect）", example = "buy")
                    String side,
            @Schema(
                            description = "PERP 四向意图（OPEN_LONG | OPEN_SHORT | CLOSE_LONG | CLOSE_SHORT；SPOT 行为 null）",
                            example = "OPEN_LONG",
                            nullable = true)
                    String positionEffect,
            @Schema(description = "强平成交行标记（PERP bar 极值近似；SPOT 行恒 false）", example = "false") boolean liquidation,
            @Schema(description = "成交价格（金额，精度 8 位）", example = "42150.50") BigDecimal price,
            @Schema(description = "成交数量（精度 8 位）", example = "0.0025") BigDecimal amount,
            @Schema(description = "手续费（精度 8 位）", example = "0.0052") BigDecimal fee,
            @Schema(description = "该笔交易的已实现盈亏（精度 8 位），首单或无配对时为 null", example = "-0.0052") BigDecimal realizedPnl,
            @Schema(description = "该笔交易后的累计权益（精度 8 位），无数据时为 null；PERP 报告恒 null（trade 口径不含未实现/资金费）")
                    BigDecimal equity) {}

    public record EquityPointDto(
            @Schema(description = "时间点", example = "2026-06-15T08:30:00Z") Instant time,
            @Schema(description = "权益（USDT 估值，精度 2 位）", example = "10532.18") BigDecimal equity,
            @Schema(description = "已用保证金（仅 PERP 报告非空；ISOLATED 为仓位保证金，CROSS 恒 0）", example = "420.21", nullable = true)
                    BigDecimal marginUsed,
            @Schema(description = "累计资金费净额（仅 PERP 报告非空；付出为负、收入为正）", example = "-12.50", nullable = true)
                    BigDecimal fundingCum) {}

    /** 组合回测分标的终仓快照。命名为 Final* 以区别 trading 模块的持仓 {@code PositionDto},
     *  避免 OpenAPI schema 同名冲突。 */
    public record FinalPositionDto(
            @Schema(description = "标的", example = "BTC/USDT") String symbol,
            @Schema(description = "持仓数量（基础币，精度 8 位）", example = "0.25") BigDecimal qty,
            @Schema(description = "持仓均价（报价币，精度 8 位）", example = "42150.50") BigDecimal avgPrice) {}
}
