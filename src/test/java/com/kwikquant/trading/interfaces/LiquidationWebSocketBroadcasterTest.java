package com.kwikquant.trading.interfaces;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.kwikquant.shared.types.LiquidationEvent;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

/**
 * 强平事件 WS 广播器单测。
 *
 * <p>验证 @EventListener 订阅 LiquidationEvent 后,convertAndSend 推到
 * {@code /topic/liquidations/{userId}},body 即事件本身(record 字段对齐契约)。
 * broker 故障时吞异常仅告警,不向上抛(避免影响 ExecutionService afterCommit 链路)。
 */
class LiquidationWebSocketBroadcasterTest {

    private SimpMessagingTemplate template;
    private LiquidationWebSocketBroadcaster broadcaster;

    @BeforeEach
    void setUp() {
        template = mock(SimpMessagingTemplate.class);
        broadcaster = new LiquidationWebSocketBroadcaster(template);
    }

    @Test
    void onLiquidation_sendsToUserTopicWithEventBody() {
        LiquidationEvent event = sampleEvent(42L, 7L, 128L, 99L);
        broadcaster.onLiquidation(event);
        verify(template).convertAndSend(eq("/topic/liquidations/42"), eq(event));
    }

    @Test
    void onLiquidation_whenOrderIdNull_stillBroadcasts() {
        // 强平无触发订单(orderId=null)是合法态,不应被拦截
        LiquidationEvent event = new LiquidationEvent(
                42L,
                null,
                7L,
                128L,
                "LONG",
                10,
                new BigDecimal("37105.00"),
                new BigDecimal("42300.00"),
                new BigDecimal("40.00"),
                new BigDecimal("-2.50"),
                "liquidation triggered at markPrice=42300.00",
                Instant.now());
        broadcaster.onLiquidation(event);
        verify(template).convertAndSend(eq("/topic/liquidations/42"), eq(event));
    }

    @Test
    void onLiquidation_propagatesAllFields() {
        // 字段对齐契约 §3.9:userId/orderId/accountId/positionId/positionSide/leverage/
        // liquidationPrice/markPrice/marginBalance/realizedPnl/reason/timestamp
        LiquidationEvent event = sampleEvent(42L, 7L, 128L, 99L);
        broadcaster.onLiquidation(event);
        org.mockito.ArgumentCaptor<Object> bodyCaptor = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(template).convertAndSend(eq("/topic/liquidations/42"), bodyCaptor.capture());
        Object body = bodyCaptor.getValue();
        assertThat(body).isInstanceOf(LiquidationEvent.class);
        LiquidationEvent sent = (LiquidationEvent) body;
        assertThat(sent.userId()).isEqualTo(42L);
        assertThat(sent.orderId()).isEqualTo(99L);
        assertThat(sent.accountId()).isEqualTo(7L);
        assertThat(sent.positionId()).isEqualTo(128L);
        assertThat(sent.positionSide()).isEqualTo("LONG");
        assertThat(sent.leverage()).isEqualTo(10);
        assertThat(sent.liquidationPrice()).isEqualByComparingTo("37105.00");
        assertThat(sent.markPrice()).isEqualByComparingTo("42300.00");
        assertThat(sent.marginBalance()).isEqualByComparingTo("40.00");
        assertThat(sent.realizedPnl()).isEqualByComparingTo("-2.50");
        assertThat(sent.reason()).contains("markPrice");
        assertThat(sent.timestamp()).isNotNull();
    }

    @Test
    void onLiquidation_whenTemplateFails_doesNotThrow() {
        doThrow(new RuntimeException("broker down")).when(template).convertAndSend(anyString(), any(Object.class));
        LiquidationEvent event = sampleEvent(42L, 7L, 128L, 99L);
        assertThatCode(() -> broadcaster.onLiquidation(event)).doesNotThrowAnyException();
    }

    private static LiquidationEvent sampleEvent(long userId, long accountId, long positionId, Long orderId) {
        return new LiquidationEvent(
                userId,
                orderId,
                accountId,
                positionId,
                "LONG",
                10,
                new BigDecimal("37105.00"),
                new BigDecimal("42300.00"),
                new BigDecimal("40.00"),
                new BigDecimal("-2.50"),
                "liquidation triggered at markPrice=42300.00",
                Instant.now());
    }
}
