package com.kwikquant.market.infrastructure;

import static org.assertj.core.api.Assertions.*;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.access.AccessDeniedException;

/**
 * Unit tests for {@link StompSubscriptionInterceptor} (M5 subscription authorization).
 *
 * <p>Verifies that a client may only SUBSCRIBE to {@code /topic/{any}/{userId}} topics whose
 * trailing userId matches the authenticated userId stored in the WebSocket session attributes
 * by {@code WebSocketAuthInterceptor}.
 */
class StompSubscriptionInterceptorTest {

    private final StompSubscriptionInterceptor interceptor = new StompSubscriptionInterceptor();

    @Test
    void preSend_whenSubscribeOwnUserId_allows() {
        Message<?> msg = subscribeMessage("/topic/notifications/123", "123");

        Message<?> result = interceptor.preSend(msg, null);

        assertThat(result).isSameAs(msg);
    }

    @Test
    void preSend_whenSubscribeOtherUserId_throwsAccessDenied() {
        // Authenticated as 123, but attempting to subscribe to 999's topic
        Message<?> msg = subscribeMessage("/topic/notifications/999", "123");

        assertThatThrownBy(() -> interceptor.preSend(msg, null))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("subscribe");
    }

    @Test
    void preSend_whenNonSubscribe_passesThrough() {
        // SEND commands (client -> server app destinations) are not gated
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setDestination("/app/trade");
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("userId", "123");
        accessor.setSessionAttributes(attrs);
        Message<?> msg = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        Message<?> result = interceptor.preSend(msg, null);

        assertThat(result).isSameAs(msg);
    }

    @Test
    void preSend_whenSessionAttributesNull_throwsAccessDenied() {
        // Unauthenticated handshake left no session attributes — must reject subscription
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination("/topic/notifications/123");
        Message<?> msg = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        assertThatThrownBy(() -> interceptor.preSend(msg, null)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void preSend_whenSubscribeOrdersTopicOwnUserId_allows() {
        // Protects all /topic/*/{userId} topics (orders/fills/positions/notifications)
        Message<?> msg = subscribeMessage("/topic/orders/123", "123");

        Message<?> result = interceptor.preSend(msg, null);

        assertThat(result).isSameAs(msg);
    }

    private Message<?> subscribeMessage(String destination, String authUserId) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination(destination);
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("userId", authUserId);
        accessor.setSessionAttributes(attrs);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
