package com.kwikquant.notification.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.kwikquant.notification.domain.NotificationChannelType;
import com.kwikquant.notification.domain.NotificationEventType;
import com.kwikquant.notification.infrastructure.NotificationPreferenceMapper;
import com.kwikquant.shared.types.AccountId;
import com.kwikquant.shared.types.LiquidationEvent;
import com.kwikquant.shared.types.OrderId;
import com.kwikquant.shared.types.OrderStatus;
import com.kwikquant.shared.types.OrderStatusChangedEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Pure-Mockito unit tests for {@link NotificationService#onOrderStatusChanged} — the branch
 * the integration test ({@link NotificationServiceTest}) does not exercise.
 *
 * <p>Covers:
 * <ul>
 *   <li>FILLED → {@link NotificationEventType#ORDER_FILLED} dispatch with title "Order Filled".</li>
 *   <li>CANCELLED → {@link NotificationEventType#ORDER_CANCELLED} dispatch with title
 *       "Order Cancelled".</li>
 *   <li>Non-terminal status (NEW) → {@code mapOrderStatus} returns null → early return, no
 *       dispatch.</li>
 *   <li>Channel.send throws → outer catch-all swallows it, no propagation.</li>
 *   <li>Preference for an unregistered channel type → {@code dispatch} skips the null channel
 *       (defensive {@code if (channel != null)} false branch).</li>
 *   <li>No preferences configured → defaults to WEBSOCKET.</li>
 * </ul>
 */
class NotificationServiceUnitTest {

    private static final long USER_ID = 7777L;

    private static OrderStatusChangedEvent event(OrderStatus previous, OrderStatus next) {
        return new OrderStatusChangedEvent(
                USER_ID, new OrderId(100L), new AccountId(1L), previous, next, Instant.parse("2026-06-30T12:00:00Z"));
    }

    private NotificationService serviceWith(NotificationChannel... channels) {
        NotificationPreferenceMapper mapper = mock(NotificationPreferenceMapper.class);
        return new NotificationService(mapper, List.of(channels));
    }

    @Test
    void onOrderStatusChanged_filled_dispatchesOrderFilled() {
        NotificationChannel webSocket = mock(NotificationChannel.class);
        when(webSocket.channelType()).thenReturn(NotificationChannelType.WEBSOCKET);
        NotificationService service = serviceWith(webSocket);

        // No preferences → defaults to WEBSOCKET
        service.onOrderStatusChanged(event(OrderStatus.SUBMITTED, OrderStatus.FILLED));

        verify(webSocket).send(eq(USER_ID), eq("Order Filled"), anyMap());
    }

    @Test
    void onOrderStatusChanged_cancelled_dispatchesOrderCancelled() {
        NotificationChannel webSocket = mock(NotificationChannel.class);
        when(webSocket.channelType()).thenReturn(NotificationChannelType.WEBSOCKET);
        NotificationService service = serviceWith(webSocket);

        service.onOrderStatusChanged(event(OrderStatus.PENDING_CANCEL, OrderStatus.CANCELLED));

        verify(webSocket).send(eq(USER_ID), eq("Order Cancelled"), anyMap());
    }

    @Test
    void onOrderStatusChanged_nonTerminalStatus_doesNotDispatch() {
        NotificationChannel webSocket = mock(NotificationChannel.class);
        when(webSocket.channelType()).thenReturn(NotificationChannelType.WEBSOCKET);
        NotificationService service = serviceWith(webSocket);

        // When: 触发 SUBMITTED → PARTIALLY_FILLED（非 FILLED/CANCELLED 终态） → mapOrderStatus 返回 null → 早退。
        service.onOrderStatusChanged(event(OrderStatus.SUBMITTED, OrderStatus.PARTIALLY_FILLED));

        // Then: 未映射到事件类型时不应下派任何 send。
        // (Cannot use verifyNoInteractions: the constructor calls channel.channelType() to
        // register the channel, so we assert send() is never invoked instead.)
        verify(webSocket, never()).send(anyLong(), anyString(), anyMap());
    }

    @Test
    void onOrderStatusChanged_whenChannelSendThrows_doesNotPropagate() {
        NotificationChannel webSocket = mock(NotificationChannel.class);
        when(webSocket.channelType()).thenReturn(NotificationChannelType.WEBSOCKET);
        doThrow(new RuntimeException("broker down")).when(webSocket).send(anyLong(), anyString(), anyMap());
        NotificationService service = serviceWith(webSocket);

        // catch-all: a channel failure must not propagate to the caller
        assertThatCode(() -> service.onOrderStatusChanged(event(OrderStatus.SUBMITTED, OrderStatus.FILLED)))
                .doesNotThrowAnyException();
    }

    @Test
    void onOrderStatusChanged_whenNoChannelRegistered_silentlySkipsNullChannel() {
        // No channels registered → channelMap.get(WEBSOCKET) == null → dispatch must skip
        // (defensive if (channel != null) false branch) without throwing.
        NotificationPreferenceMapper mapper = mock(NotificationPreferenceMapper.class);
        when(mapper.findByUserIdAndEventType(eq(USER_ID), eq(NotificationEventType.ORDER_FILLED.name())))
                .thenReturn(List.of());
        NotificationService service = new NotificationService(mapper, List.of());

        // Defaults to WEBSOCKET (no prefs), but no WEBSOCKET channel is registered → skip
        assertThatCode(() -> service.onOrderStatusChanged(event(OrderStatus.SUBMITTED, OrderStatus.FILLED)))
                .doesNotThrowAnyException();
        // Strong assertion: dispatch 确实执行到了（进 mapper.findByUserIdAndEventType 之后才走到 null channel skip 分支）
        verify(mapper).findByUserIdAndEventType(eq(USER_ID), eq(NotificationEventType.ORDER_FILLED.name()));
    }

    @Test
    void onOrderStatusChanged_payloadContainsOrderDetails() {
        NotificationChannel webSocket = mock(NotificationChannel.class);
        when(webSocket.channelType()).thenReturn(NotificationChannelType.WEBSOCKET);
        NotificationService service = serviceWith(webSocket);

        service.onOrderStatusChanged(event(OrderStatus.SUBMITTED, OrderStatus.FILLED));

        org.mockito.ArgumentCaptor<Map<String, Object>> captor = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(webSocket).send(eq(USER_ID), anyString(), captor.capture());
        Map<String, Object> payload = captor.getValue();
        assertThat(payload.get("type")).isEqualTo("ORDER_FILLED");
        assertThat(payload.get("orderId")).isEqualTo(100L);
        assertThat(payload.get("accountId")).isEqualTo(1L);
        assertThat(payload.get("previousStatus")).isEqualTo("SUBMITTED");
        assertThat(payload.get("newStatus")).isEqualTo("FILLED");
    }

    /**
     * 强平通知( {@link NotificationService#onLiquidation} )此前两个通知测试类都未调用,
     * 整方法体零覆盖。下面两条用例补强强平链路,并校 {@code orderId}/{@code realizedPnl} 可空
     * 分支的 payload 语义(onLiquidation L150、L155)。
     */
    private static LiquidationEvent liquidationEvent(Long orderId, BigDecimal realizedPnl) {
        return new LiquidationEvent(
                USER_ID,
                orderId,
                1L,
                128L,
                "LONG",
                10,
                new BigDecimal("59000"),
                new BigDecimal("60000"),
                new BigDecimal("52.00"),
                realizedPnl,
                "维持保证金不足",
                Instant.parse("2026-08-05T00:00:00Z"));
    }

    @Test
    void onLiquidation_withOrderIdAndRealizedPnl_dispatchesWithFullPayload() {
        NotificationChannel webSocket = mock(NotificationChannel.class);
        when(webSocket.channelType()).thenReturn(NotificationChannelType.WEBSOCKET);
        NotificationService service = serviceWith(webSocket);

        service.onLiquidation(liquidationEvent(200L, new BigDecimal("-3.50")));

        org.mockito.ArgumentCaptor<Map<String, Object>> captor = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(webSocket).send(eq(USER_ID), eq("持仓已被强平"), captor.capture());
        Map<String, Object> payload = captor.getValue();
        assertThat(payload.get("type")).isEqualTo("LIQUIDATION");
        assertThat(payload.get("accountId")).isEqualTo(1L);
        assertThat(payload.get("positionId")).isEqualTo(128L);
        assertThat(payload.get("positionSide")).isEqualTo("LONG");
        assertThat(payload.get("orderId")).isEqualTo(200L);
        // realizedPnl 非空 → toPlainString 原样
        assertThat(payload.get("realizedPnl")).isEqualTo("-3.50");
        assertThat(payload.get("reason")).isEqualTo("维持保证金不足");
        assertThat(payload.get("timestamp")).isEqualTo("2026-08-05T00:00:00Z");
    }

    @Test
    void onLiquidation_whenNoOrderIdAndNullRealizedPnl_usesZeroPnlAndOmitsOrderId() {
        NotificationChannel webSocket = mock(NotificationChannel.class);
        when(webSocket.channelType()).thenReturn(NotificationChannelType.WEBSOCKET);
        NotificationService service = serviceWith(webSocket);

        // 系统强平无触发订单(orderId=null)+ realizedPnl 未算出(null)
        service.onLiquidation(liquidationEvent(null, null));

        org.mockito.ArgumentCaptor<Map<String, Object>> captor = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(webSocket).send(eq(USER_ID), eq("持仓已被强平"), captor.capture());
        Map<String, Object> payload = captor.getValue();
        assertThat(payload.get("type")).isEqualTo("LIQUIDATION");
        assertThat(payload).doesNotContainKey("orderId");
        // realizedPnl=null → 容错用字符串 "0"
        assertThat(payload.get("realizedPnl")).isEqualTo("0");
    }
}
