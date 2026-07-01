package com.kwikquant.notification.infrastructure;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

/**
 * Unit tests for {@link WebSocketNotificationChannel}.
 *
 * <p>Verifies the channel routes payloads to {@code /topic/notifications/{userId}} and that
 * messaging-template failures are caught (catch-all) so they never propagate to the caller
 * (tech-design §3.4.6: WebSocket push failure → catch-all + log.warn, skip channel).
 */
class WebSocketNotificationChannelTest {

    private SimpMessagingTemplate messagingTemplate;
    private WebSocketNotificationChannel channel;

    @BeforeEach
    void setUp() {
        messagingTemplate = mock(SimpMessagingTemplate.class);
        channel = new WebSocketNotificationChannel(messagingTemplate);
    }

    @Test
    void send_pushesToUserTopic() {
        Map<String, Object> payload = Map.of("type", "RISK_REJECTED", "reason", "MAX_NOTIONAL triggered");

        channel.send(123L, "Risk Alert", payload);

        verify(messagingTemplate).convertAndSend(eq("/topic/notifications/123"), any(Object.class));
    }

    @Test
    void send_whenMessagingTemplateFails_doesNotThrow() {
        doThrow(new RuntimeException("simulated broker failure"))
                .when(messagingTemplate)
                .convertAndSend(anyString(), any(Object.class));
        Map<String, Object> payload = Map.of("type", "RISK_REJECTED");

        // catch-all inside send() must swallow the exception
        assertThatCode(() -> channel.send(123L, "Risk Alert", payload))
                .doesNotThrowAnyException();
    }

    @Test
    void channelType_isWebSocket() {
        assertThat(channel.channelType().name()).isEqualTo("WEBSOCKET");
    }
}
