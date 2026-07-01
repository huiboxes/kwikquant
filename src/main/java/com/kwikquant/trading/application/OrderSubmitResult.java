package com.kwikquant.trading.application;

import com.kwikquant.shared.types.OrderStatus;
import com.kwikquant.trading.domain.Order;
import com.kwikquant.trading.domain.OrderSubmitCommand;
import java.time.Instant;

/** 下单返回结果。 */
public record OrderSubmitResult(long orderId, OrderStatus status, long version, Instant createdAt) {

    public static OrderSubmitResult from(Order order, OrderSubmitCommand cmd) {
        return new OrderSubmitResult(order.getId(), order.getStatus(), order.getVersion(), order.getCreatedAt());
    }

    /**
     * Factory for risk-rejected results.
     *
     * @param order the rejected order
     * @return result with REJECTED status
     */
    public static OrderSubmitResult rejected(Order order) {
        return new OrderSubmitResult(order.getId(), order.getStatus(), order.getVersion(), order.getCreatedAt());
    }
}
