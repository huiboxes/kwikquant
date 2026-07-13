package com.kwikquant.trading.interfaces;

import com.kwikquant.shared.types.OrderStatus;
import java.time.Instant;

/**
 * WebSocket 订单状态变更事件。
 */
public record OrderEvent(
        String eventType,
        Long orderId,
        Long accountId,
        OrderStatus previousStatus,
        OrderStatus newStatus,
        Long version,
        Instant occurredAt) {

    public static OrderEvent statusChanged(
            Long orderId, Long accountId, OrderStatus previousStatus, OrderStatus newStatus, Long version) {
        return new OrderEvent("STATUS_CHANGED", orderId, accountId, previousStatus, newStatus, version, Instant.now());
    }
}
