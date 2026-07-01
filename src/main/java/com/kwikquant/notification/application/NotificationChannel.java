package com.kwikquant.notification.application;

import com.kwikquant.notification.domain.NotificationChannelType;
import java.util.Map;

/**
 * SPI interface for notification delivery channels.
 *
 * <p>Implementations are discovered via Spring's {@code List<NotificationChannel>} injection
 * and dispatched by {@link NotificationService} based on user preferences.
 */
public interface NotificationChannel {

    /**
     * Returns the channel type this implementation handles.
     *
     * @return the channel type
     */
    NotificationChannelType channelType();

    /**
     * Sends a notification to the specified user.
     *
     * @param userId  the target user id
     * @param title   a short title for the notification
     * @param payload key-value payload with notification details
     */
    void send(long userId, String title, Map<String, Object> payload);
}
