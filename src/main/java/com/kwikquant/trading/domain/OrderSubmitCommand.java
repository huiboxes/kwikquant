package com.kwikquant.trading.domain;

import com.kwikquant.shared.types.MarketType;
import com.kwikquant.shared.types.OrderSide;
import com.kwikquant.shared.types.OrderType;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * 下单命令。REST controller / SDK / MCP 调用 TradingService.submit 时传入。
 *
 * <p>注意：{@code currentUserId} <strong>不</strong> 在 Command 中——由 TradingService 内部通过 {@code
 * SecurityUtils.currentUserId()} 获取，避免客户端伪造。
 */
public record OrderSubmitCommand(
        long accountId,
        String symbol,
        MarketType marketType,
        OrderSide side,
        OrderType orderType,
        BigDecimal amount,
        BigDecimal price,
        BigDecimal stopPrice,
        TimeInForce timeInForce,
        Instant expireAt,
        String clientOrderId) {}
