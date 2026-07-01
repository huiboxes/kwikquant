package com.kwikquant.trading.interfaces;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * WebSocket 持仓更新事件。
 */
public record PositionEvent(
        String eventType,
        Long positionId,
        Long accountId,
        String symbol,
        String side,
        BigDecimal qty,
        BigDecimal avgEntryPrice,
        BigDecimal realizedPnl,
        Long version,
        Instant updatedAt) {

    public static PositionEvent of(PositionDto position) {
        return new PositionEvent(
                "POSITION_UPDATED",
                position.positionId(),
                position.accountId(),
                position.symbol(),
                position.side(),
                position.qty(),
                position.avgEntryPrice(),
                position.realizedPnl(),
                position.version(),
                position.updatedAt());
    }
}
