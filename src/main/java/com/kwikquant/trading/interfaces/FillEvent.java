package com.kwikquant.trading.interfaces;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * WebSocket 成交事件。
 *
 * <p>{@code positionEffect}:PERP 订单的四向意图(OPEN_LONG/OPEN_SHORT/CLOSE_LONG/CLOSE_SHORT,
 * 取自成交所属 Order;side 对 PERP 是派生量,buy≠开仓),SPOT 订单为 null。runner 策略
 * {@code on_fill} 回调依赖此字段还原开平语义(docs/strategy-api.md §8,与回测引擎派发同构)。
 *
 * <p>{@code marketType}:成交所属 Order 的市场类型(SPOT|PERP)。fills topic 是 user 级
 * (跨账户跨市场类型),runner 事件回调按 accountId+marketType+symbol 过滤派发
 * (docs/ws-contract.md §5)——同一账户 SPOT 与 PERP 可同 symbol 并存,缺此字段两路回调互相污染。
 */
public record FillEvent(
        String eventType,
        Long fillId,
        Long orderId,
        Long accountId,
        String symbol,
        String side,
        BigDecimal price,
        BigDecimal qty,
        BigDecimal fee,
        String feeCurrency,
        String liquidity,
        String positionEffect,
        String marketType,
        Instant filledAt) {

    public static FillEvent of(FillDto fill, String positionEffect, String marketType) {
        return new FillEvent(
                "NEW_FILL",
                fill.fillId(),
                fill.orderId(),
                fill.accountId(),
                fill.symbol(),
                fill.side(),
                fill.price(),
                fill.qty(),
                fill.fee(),
                fill.feeCurrency(),
                fill.liquidity(),
                positionEffect,
                marketType,
                fill.filledAt());
    }
}
