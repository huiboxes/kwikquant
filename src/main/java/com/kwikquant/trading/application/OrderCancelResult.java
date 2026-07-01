package com.kwikquant.trading.application;

import com.kwikquant.shared.types.OrderStatus;
import com.kwikquant.trading.domain.Order;

/** 撤单返回结果。 */
public record OrderCancelResult(long orderId, OrderStatus status, long version) {

    public static OrderCancelResult from(Order order) {
        return new OrderCancelResult(order.getId(), order.getStatus(), order.getVersion());
    }
}
