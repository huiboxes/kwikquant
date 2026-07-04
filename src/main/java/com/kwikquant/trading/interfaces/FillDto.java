package com.kwikquant.trading.interfaces;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * 成交记录响应 DTO。
 */
public record FillDto(
        @Schema(description = "成交 ID", example = "1024") Long fillId,
        @Schema(description = "订单 ID", example = "42") Long orderId,
        @Schema(description = "账户 ID", example = "7") Long accountId,
        @Schema(description = "canonical symbol", example = "BTC/USDT") String symbol,
        @Schema(description = "方向（小写: buy | sell）", example = "buy") String side,
        @Schema(description = "成交价格（精度 8 位）", example = "42150.50") BigDecimal price,
        @Schema(description = "成交数量（精度 8 位）", example = "0.0025") BigDecimal qty,
        @Schema(description = "手续费（精度 8 位）", example = "0.0052") BigDecimal fee,
        @Schema(description = "手续费币种", example = "USDT") String feeCurrency,
        @Schema(description = "流动性方向（枚举: taker | maker）", example = "taker") String liquidity,
        @Schema(description = "交易所成交 ID") String externalFillId,
        @Schema(description = "成交时间", example = "2026-07-04T12:00:05Z") Instant filledAt) {}
