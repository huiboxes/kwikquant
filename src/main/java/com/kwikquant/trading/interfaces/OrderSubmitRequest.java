package com.kwikquant.trading.interfaces;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

/**
 * POST /api/v1/orders 请求体。
 *
 * @param accountId   账户 ID（用户请求必须属于当前用户;Wave 8 §3.7 R4:Worker 请求由 filter 从 token 推导,accountId 可 null）
 * @param symbol      canonical symbol（如 BTC/USDT）
 * @param side        buy / sell
 * @param orderType   market / limit / stop_market / stop_limit / take_profit_market / take_profit_limit / trailing_stop
 * @param amount      下单数量（> 0）
 * @param price       限价类必填（> 0）
 * @param stopPrice   stop 类必填（> 0）
 * @param timeInForce GTC / IOC / FOK / GTD（默认 GTC）
 * @param expireAt    GTD 必填（ISO-8601 UTC）
 * @param clientOrderId 调用方幂等 token（最长 64 字符）
 * @param marketType  SPOT / PERP（ExchangeAccount 不含此字段，调用方显式传入）
 */
public record OrderSubmitRequest(
        Long accountId,
        @NotBlank String symbol,
        @NotBlank String side,
        @NotBlank String orderType,
        @jakarta.validation.constraints.NotNull @Positive BigDecimal amount,
        @DecimalMin("0") BigDecimal price,
        @DecimalMin("0") BigDecimal stopPrice,
        String timeInForce,
        String expireAt,
        String clientOrderId,
        @NotBlank String marketType) {}
