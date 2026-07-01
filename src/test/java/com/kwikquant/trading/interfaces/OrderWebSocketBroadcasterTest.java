package com.kwikquant.trading.interfaces;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

class OrderWebSocketBroadcasterTest {
    private SimpMessagingTemplate template;
    private OrderWebSocketBroadcaster broadcaster;

    @BeforeEach
    void setUp() {
        template = mock(SimpMessagingTemplate.class);
        broadcaster = new OrderWebSocketBroadcaster(template);
    }

    @Test
    void broadcastOrderEvent_sendsToUserTopic() {
        OrderEvent event = OrderEvent.statusChanged(1L, 42L, "NEW", "SUBMITTED", 2L);
        broadcaster.broadcast(42L, event);
        verify(template).convertAndSend(eq("/topic/orders/42"), eq(event));
    }

    @Test
    void broadcastFillEvent_sendsToUserTopic() {
        FillEvent event = FillEvent.of(new FillDto(1L, 100L, 42L, "BTC/USDT", "BUY",
                new BigDecimal("40000"), new BigDecimal("1"), BigDecimal.ZERO, "USDT", "taker", "ext-1", Instant.now()));
        broadcaster.broadcast(42L, event);
        verify(template).convertAndSend(eq("/topic/fills/42"), eq(event));
    }

    @Test
    void broadcastPositionEvent_sendsToUserTopic() {
        PositionEvent event = new PositionEvent("POSITION_UPDATED", 1L, 42L, "BTC/USDT", "long",
                BigDecimal.ONE, BigDecimal.TEN, BigDecimal.ZERO, 1L, Instant.now());
        broadcaster.broadcast(42L, event);
        verify(template).convertAndSend(eq("/topic/positions/42"), eq(event));
    }

    @Test
    void broadcastOrderEvent_whenTemplateFails_doesNotThrow() {
        doThrow(new RuntimeException("broker down")).when(template).convertAndSend(anyString(), any(Object.class));
        OrderEvent event = OrderEvent.statusChanged(1L, 42L, "NEW", "SUBMITTED", 2L);
        assertThatCode(() -> broadcaster.broadcast(42L, event)).doesNotThrowAnyException();
    }

    @Test
    void broadcastFillEvent_whenTemplateFails_doesNotThrow() {
        doThrow(new RuntimeException("broker down")).when(template).convertAndSend(anyString(), any(Object.class));
        FillEvent event = FillEvent.of(new FillDto(1L, 100L, 42L, "BTC/USDT", "BUY",
                new BigDecimal("40000"), new BigDecimal("1"), BigDecimal.ZERO, "USDT", "taker", "ext-1", Instant.now()));
        assertThatCode(() -> broadcaster.broadcast(42L, event)).doesNotThrowAnyException();
    }

    @Test
    void broadcastPositionEvent_whenTemplateFails_doesNotThrow() {
        doThrow(new RuntimeException("broker down")).when(template).convertAndSend(anyString(), any(Object.class));
        PositionEvent event = new PositionEvent("POSITION_UPDATED", 1L, 42L, "BTC/USDT", "long",
                BigDecimal.ONE, BigDecimal.TEN, BigDecimal.ZERO, 1L, Instant.now());
        assertThatCode(() -> broadcaster.broadcast(42L, event)).doesNotThrowAnyException();
    }
}
