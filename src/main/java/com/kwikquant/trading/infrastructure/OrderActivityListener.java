package com.kwikquant.trading.infrastructure;

import com.kwikquant.shared.types.ActivityCreatedEvent;
import com.kwikquant.shared.types.ActivityTypes;
import com.kwikquant.shared.types.OrderStatus;
import com.kwikquant.shared.types.OrderStatusChangedEvent;
import com.kwikquant.trading.domain.Order;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class OrderActivityListener {

    private static final Logger log = LoggerFactory.getLogger(OrderActivityListener.class);
    private final ApplicationEventPublisher publisher;
    private final OrderMapper orderMapper;

    public OrderActivityListener(ApplicationEventPublisher publisher, OrderMapper orderMapper) {
        this.publisher = publisher;
        this.orderMapper = orderMapper;
    }

    @EventListener
    public void onOrderStatusChanged(OrderStatusChangedEvent event) {
        if (event.newStatus() != OrderStatus.FILLED) {
            return;
        }

        try {
            Order order = orderMapper.findById(event.orderId().value());
            if (order == null) {
                return;
            }

            String title = String.format("%s %s %s @ %s",
                    order.getSymbol(),
                    order.getSide(),
                    order.getFilledQty(),
                    order.getFilledAvgPrice());
            String subtitle = order.getExchange() + " · 全部成交";

            publisher.publishEvent(new ActivityCreatedEvent(
                    event.userId(),
                    ActivityTypes.ORDER_FILLED,
                    title,
                    subtitle,
                    event.timestamp()));
        } catch (Exception e) {
            log.debug("[activity] failed to convert order event orderId={}: {}", event.orderId().value(), e.getMessage());
        }
    }
}
