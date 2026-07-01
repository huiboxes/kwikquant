package com.kwikquant.notification.infrastructure;

import com.kwikquant.notification.application.NotificationChannel;
import com.kwikquant.notification.domain.NotificationChannelType;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * WebSocket (STOMP) notification channel implementation.
 *
 * <p>Sends notification payloads to user-specific STOMP topics:
 * {@code /topic/notifications/{userId}}.
 */
@Component
public class WebSocketNotificationChannel implements NotificationChannel {

    private static final Logger log = LoggerFactory.getLogger(WebSocketNotificationChannel.class);

    private final SimpMessagingTemplate messagingTemplate;

    public WebSocketNotificationChannel(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    @Override
    public NotificationChannelType channelType() {
        return NotificationChannelType.WEBSOCKET;
    }

    @Override
    public void send(long userId, String title, Map<String, Object> payload) {
        try {
            // Cast to Object to disambiguate convertAndSend(D, Object) vs convertAndSend(Object, Map);
            // payload (Map<String,Object>) would otherwise match both overloads.
            messagingTemplate.convertAndSend("/topic/notifications/" + userId, (Object) payload);
        } catch (Exception e) {
            log.warn("[notification] WebSocket send failed for userId={}: {}", userId, e.getMessage());
        }
    }
}
