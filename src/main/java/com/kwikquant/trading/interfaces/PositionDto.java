package com.kwikquant.trading.interfaces;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;

/** 持仓响应 DTO。 */
public record PositionDto(
        @Schema(description = "持仓 ID", example = "128") Long positionId,
        @Schema(description = "账户 ID", example = "7") Long accountId,
        @Schema(description = "canonical symbol", example = "BTC/USDT") String symbol,
        @Schema(description = "持仓方向（枚举: LONG | SHORT | FLAT）", example = "LONG") String side,
        @Schema(description = "持仓数量（精度 8 位）", example = "0.0025") BigDecimal qty,
        @Schema(description = "平均开仓价（精度 8 位）", example = "42150.50") BigDecimal avgEntryPrice,
        @Schema(description = "已实现盈亏（USDT 估值口径，精度 2 位，负值为亏损）", example = "32.15") BigDecimal realizedPnl,
        @Schema(description = "未实现盈亏（USDT 估值口径，精度 2 位）。行情不可用时为 null", example = "15.30") BigDecimal unrealizedPnl,
        @Schema(description = "当前市价。行情不可用时为 null", example = "42300.00") BigDecimal currentPrice,
        @Schema(description = "版本号（乐观锁）", example = "1") Long version,
        @Schema(description = "最后更新时间", example = "2026-07-04T12:00:05Z") Instant updatedAt) {}
