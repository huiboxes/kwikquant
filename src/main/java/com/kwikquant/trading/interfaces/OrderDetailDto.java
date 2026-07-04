package com.kwikquant.trading.interfaces;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;

/** 订单详情响应 DTO。 */
public record OrderDetailDto(
        @Schema(description = "订单 ID", example = "42") Long orderId,
        @Schema(description = "账户 ID", example = "7") Long accountId,
        @Schema(description = "canonical symbol", example = "BTC/USDT") String symbol,
        @Schema(description = "市场类型（SPOT | PERP）", example = "SPOT") String marketType,
        @Schema(description = "方向（小写: buy | sell）", example = "buy") String side,
        @Schema(description = "订单类型（小写）", example = "limit") String orderType,
        @Schema(description = "下单数量", example = "0.1") BigDecimal amount,
        @Schema(description = "限价（MARKET 为 null）", example = "42150.50") BigDecimal price,
        @Schema(description = "止损价", example = "40000") BigDecimal stopPrice,
        @Schema(description = "有效期（GTC | IOC | FOK | GTD）", example = "GTC") String timeInForce,
        @Schema(description = "GTD 过期时间") Instant expireAt,
        @Schema(
                description =
                        "订单状态（枚举: NEW | PARTIAL | FILLED | CANCELLED | REJECTED | EXPIRED）",
                example = "FILLED")
                String status,
        @Schema(description = "已成交数量", example = "0.1") BigDecimal filledQty,
        @Schema(description = "成交均价", example = "42150.50") BigDecimal filledAvgPrice,
        @Schema(description = "客户端订单标识") String clientOrderId,
        @Schema(description = "交易所订单 ID") String exchangeOrderId,
        @Schema(description = "版本号（乐观锁）", example = "1") Long version,
        @Schema(description = "创建时间") Instant createdAt,
        @Schema(description = "最后更新时间") Instant updatedAt) {}
