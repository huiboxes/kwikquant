package com.kwikquant.report.interfaces;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;

public record TradeHistoryDto(
        @Schema(description = "订单 ID", example = "1024") long orderId,
        @Schema(description = "账户 ID", example = "42") long accountId,
        @Schema(description = "canonical symbol", example = "BTC/USDT") String symbol,
        @Schema(description = "方向（枚举: buy | sell）", example = "buy") String side,
        @Schema(description = "订单类型（枚举: limit | market | stop | stop_limit）", example = "limit") String orderType,
        @Schema(description = "委托数量（精度 8 位）", example = "0.0025") BigDecimal amount,
        @Schema(description = "已成交数量（精度 8 位）", example = "0.0025") BigDecimal filledQty,
        @Schema(description = "成交均价（精度 8 位）", example = "42150.50") BigDecimal filledAvgPrice,
        @Schema(description = "累计手续费（按订单聚合，精度 8 位）", example = "0.0052") BigDecimal totalFee,
        @Schema(description = "成交额（按订单聚合，USDT，精度 2 位）", example = "105.38") BigDecimal totalVolume,
        @Schema(description = "订单状态（枚举: NEW | PARTIAL | FILLED | CANCELLED | REJECTED | EXPIRED）", example = "FILLED")
                String status,
        @Schema(description = "创建时间", example = "2026-07-04T12:00:00Z") Instant createdAt,
        @Schema(description = "最后更新时间", example = "2026-07-04T12:00:05Z") Instant updatedAt) {}
