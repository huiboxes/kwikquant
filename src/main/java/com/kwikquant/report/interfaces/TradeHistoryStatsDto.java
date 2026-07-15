package com.kwikquant.report.interfaces;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

public record TradeHistoryStatsDto(
        @Schema(description = "总成交额（USDT 估值，精度 2 位）", example = "152340.50") BigDecimal totalVolume,
        @Schema(description = "累计手续费（USDT，精度 2 位）", example = "76.17") BigDecimal totalFees,
        @Schema(description = "已实现盈亏（USDT，精度 2 位，负值为亏损）", example = "3210.45") BigDecimal realizedPnl,
        @Schema(description = "总交易天数", example = "7") long tradeCount,
        @Schema(description = "按日胜率（0~1，精度 4 位；无交易时为 null）", example = "0.5714") BigDecimal winRate) {}
