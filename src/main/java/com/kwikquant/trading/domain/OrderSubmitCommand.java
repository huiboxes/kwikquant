package com.kwikquant.trading.domain;

import com.kwikquant.shared.types.MarginMode;
import com.kwikquant.shared.types.MarketType;
import com.kwikquant.shared.types.OrderSide;
import com.kwikquant.shared.types.OrderType;
import com.kwikquant.shared.types.PositionEffect;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * 下单命令。REST controller / SDK / MCP 调用 TradingService.submit 时传入。
 *
 * <p>注意：{@code currentUserId} <strong>不</strong> 在 Command 中——由 TradingService 内部通过 {@code
 * SecurityUtils.currentUserId()} 获取，避免客户端伪造。
 *
 * <p>合约字段(§13 拍板):{@code leverage/marginMode/positionEffect} 仅 PERP 必填,SPOT 传 null。
 * {@code reduceOnly} <strong>不入 Command</strong>(纯派生,{@link Order#isReduceOnly()} 从 positionEffect
 * 派生 CLOSE_*→true,§13 拍板 3)。
 *
 * <p>静态工厂 {@link #spot} / {@link #perp} 集中构造:SPOT 调用点用 {@code spot(11 参数,合约字段 null)},
 * PERP 用 {@code perp(14 参数,合约字段必填)}。原 {@code new OrderSubmitCommand(11 参数)} 已扩为 14 参数
 * canonical constructor,旧调用点迁移到 {@code spot} 工厂(参数不变,最小迁移)。
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
        String clientOrderId,
        Integer leverage,
        MarginMode marginMode,
        PositionEffect positionEffect) {

    /**
     * SPOT 下单工厂(§11 M2-new):合约字段全 null。marketType 参数保留兼容(调用方传 SPOT),
     * PERP 下单请用 {@link #perp}。原 {@code new OrderSubmitCommand(11 参数)} 调用点迁移到本工厂
     * (参数顺序/个数不变,仅 {@code new} → {@code OrderSubmitCommand.spot})。
     */
    public static OrderSubmitCommand spot(
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
            String clientOrderId) {
        return new OrderSubmitCommand(
                accountId,
                symbol,
                marketType,
                side,
                orderType,
                amount,
                price,
                stopPrice,
                timeInForce,
                expireAt,
                clientOrderId,
                null,
                null,
                null);
    }

    /** PERP 合约下单工厂(§11 M2-new):合约字段必填,marketType 固定 PERP。 */
    public static OrderSubmitCommand perp(
            long accountId,
            String symbol,
            OrderSide side,
            OrderType orderType,
            BigDecimal amount,
            BigDecimal price,
            BigDecimal stopPrice,
            TimeInForce timeInForce,
            Instant expireAt,
            String clientOrderId,
            Integer leverage,
            MarginMode marginMode,
            PositionEffect positionEffect) {
        return new OrderSubmitCommand(
                accountId,
                symbol,
                MarketType.PERP,
                side,
                orderType,
                amount,
                price,
                stopPrice,
                timeInForce,
                expireAt,
                clientOrderId,
                leverage,
                marginMode,
                positionEffect);
    }
}
