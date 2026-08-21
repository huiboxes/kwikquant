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

    @Test
    void onOrderStatusChanged_nullEvent_swallowedByCatchAll() {
        NotificationChannel webSocket = mock(NotificationChannel.class);
        when(webSocket.channelType()).thenReturn(NotificationChannelType.WEBSOCKET);
        NotificationService service = serviceWith(webSocket);
        assertThatCode(() -> service.onOrderStatusChanged(null)).doesNotThrowAnyException();
    }

    @Test
    void onRiskTriggered_nullEvent_swallowedByCatchAll() {
        NotificationChannel webSocket = mock(NotificationChannel.class);
        when(webSocket.channelType()).thenReturn(NotificationChannelType.WEBSOCKET);
        NotificationService service = serviceWith(webSocket);
        assertThatCode(() -> service.onRiskTriggered(null)).doesNotThrowAnyException();
    }

    private static LiquidationEvent liquidation(Long orderId, BigDecimal realizedPnl) {
        return new LiquidationEvent(
                USER_ID,
                orderId,
                1L,
                9L,
                "LONG",
                5,
                new BigDecimal("100.50"),
                new BigDecimal("99.90"),
                new BigDecimal("10.00"),
                realizedPnl,
                "保证金不足",
                Instant.parse("2026-08-16T09:00:00Z"));
    }

    @Test
    void onLiquidation_withOrderId_dispatchesFullPayload() {
        NotificationChannel webSocket = mock(NotificationChannel.class);
        when(webSocket.channelType()).thenReturn(NotificationChannelType.WEBSOCKET);
        NotificationService service = serviceWith(webSocket);

        service.onLiquidation(liquidation(55L, new BigDecimal("-50.25")));

        org.mockito.ArgumentCaptor<Map<String, Object>> captor = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(webSocket).send(eq(USER_ID), eq("持仓已被强平"), captor.capture());
        Map<String, Object> payload = captor.getValue();
        assertThat(payload.get("type")).isEqualTo("LIQUIDATION");
        assertThat(payload.get("accountId")).isEqualTo(1L);
        assertThat(payload.get("orderId")).isEqualTo(55L);
        assertThat(payload.get("positionId")).isEqualTo(9L);
        assertThat(payload.get("positionSide")).isEqualTo("LONG");
        assertThat(payload.get("realizedPnl")).isEqualTo("-50.25");
        assertThat(payload.get("reason")).isEqualTo("保证金不足");
    }

    @Test
    void onLiquidation_withoutOrderId_omitsOrderIdKey_andDefaultsPnl() {
        NotificationChannel webSocket = mock(NotificationChannel.class);
        when(webSocket.channelType()).thenReturn(NotificationChannelType.WEBSOCKET);
        NotificationService service = serviceWith(webSocket);

        // 系统强平无触发订单(orderId null) + realizedPnl 未算出(null) → 兜底分支
        service.onLiquidation(liquidation(null, null));

        org.mockito.ArgumentCaptor<Map<String, Object>> captor = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(webSocket).send(eq(USER_ID), eq("持仓已被强平"), captor.capture());
        Map<String, Object> payload = captor.getValue();
        assertThat(payload).doesNotContainKey("orderId");
        assertThat(payload.get("realizedPnl")).isEqualTo("0");
    }
}
