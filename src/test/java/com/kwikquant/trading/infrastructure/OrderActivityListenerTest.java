package com.kwikquant.trading.infrastructure;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.kwikquant.shared.types.*;
import com.kwikquant.trading.domain.Order;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

class OrderActivityListenerTest {

    @Test
    void onOrderStatusChanged_filledOrder_shouldPublishActivityCreatedEvent() {
        var publisher = mock(ApplicationEventPublisher.class);
        var orderMapper = mock(OrderMapper.class);
        var listener = new OrderActivityListener(publisher, orderMapper);

        Order order = new Order();
        order.setSymbol("BTC/USDT");
        order.setSide(OrderSide.BUY);
        order.setFilledQty(new BigDecimal("0.42"));
        order.setFilledAvgPrice(new BigDecimal("61200"));
        order.setExchange(Exchange.BINANCE);
        when(orderMapper.findById(100L)).thenReturn(order);

        var event = new OrderStatusChangedEvent(
                42L,
                new OrderId(100L),
                new AccountId(1L),
                OrderStatus.SUBMITTED,
                OrderStatus.FILLED,
                Instant.now());

        listener.onOrderStatusChanged(event);

        verify(publisher).publishEvent(any(ActivityCreatedEvent.class));
    }

    @Test
    void onOrderStatusChanged_nonFilledStatus_shouldNotPublish() {
        var publisher = mock(ApplicationEventPublisher.class);
        var orderMapper = mock(OrderMapper.class);
        var listener = new OrderActivityListener(publisher, orderMapper);

        var event = new OrderStatusChangedEvent(
                42L,
                new OrderId(100L),
                new AccountId(1L),
                OrderStatus.NEW,
                OrderStatus.SUBMITTED,
                Instant.now());

        listener.onOrderStatusChanged(event);

        verify(publisher, never()).publishEvent(any());
    }
}
