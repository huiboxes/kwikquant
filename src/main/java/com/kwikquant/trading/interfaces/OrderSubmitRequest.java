package com.kwikquant.trading.interfaces;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

/**
 * POST /api/v1/orders 请求体。
 */
public record OrderSubmitRequest(
        @Schema(description = "账户 ID。用户请求必填（后端校验归属）；Worker 请求应为空（后端据 X-Worker-Token 推导）") Long accountId,
        @Schema(description = "canonical symbol（CCXT 规范，如 BTC/USDT）", example = "BTC/USDT") @NotBlank String symbol,
        @Schema(description = "方向（枚举: BUY | SELL）", example = "BUY") @NotBlank String side,
        @Schema(
                        description = "订单类型（枚举: LIMIT | MARKET | STOP | STOP_LIMIT | "
                                + "TAKE_PROFIT_MARKET | TAKE_PROFIT_LIMIT | TRAILING_STOP）",
                        example = "LIMIT")
                @NotBlank
                String orderType,
        @Schema(description = "下单数量（> 0，BigDecimal）", example = "0.1") @jakarta.validation.constraints.NotNull @Positive
                BigDecimal amount,
        @Schema(description = "限价（LIMIT 类必填，> 0；MARKET 为 null）", example = "42150.50") @DecimalMin("0")
                BigDecimal price,
        @Schema(description = "止损价（STOP 类必填，> 0）", example = "40000") @DecimalMin("0") BigDecimal stopPrice,
        @Schema(description = "有效期（枚举: GTC | IOC | FOK | GTD，默认 GTC）", example = "GTC") String timeInForce,
        @Schema(description = "GTD 过期时间（ISO-8601 UTC，GTD 必填）", example = "2026-07-04T12:00:00Z") String expireAt,
        @Schema(description = "客户端订单标识，用于关联。当前后端不保证幂等去重，前端重试需谨慎（重复提交可能创建多笔）", example = "client-abc-123")
                String clientOrderId,
        @Schema(description = "市场类型（枚举: SPOT | PERP）", example = "SPOT") @NotBlank String marketType,
        @Schema(description = "合约杠杆倍数（PERP 1-125,SPOT null）", example = "10") Integer leverage,
        @Schema(description = "合约保证金模式（PERP: ISOLATED | CROSS,SPOT null）", example = "ISOLATED") String marginMode,
        @Schema(description = "合约方向（PERP: OPEN_LONG | OPEN_SHORT | CLOSE_LONG | CLOSE_SHORT,SPOT null）", example = "OPEN_LONG") String positionEffect) {}
