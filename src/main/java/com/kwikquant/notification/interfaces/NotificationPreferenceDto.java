package com.kwikquant.notification.interfaces;

import com.kwikquant.notification.domain.NotificationPreference;
import java.time.Instant;

/**
 * Response DTO projecting a {@link NotificationPreference} for the REST API.
 *
 * @param id          preference id
 * @param userId      owning user id
 * @param eventType   notification event type name
 * @param channelType delivery channel type name
 * @param enabled     whether this combination is enabled
 * @param createdAt   creation timestamp
 * @param updatedAt   last update timestamp
 */
public record NotificationPreferenceDto(
        long id,
        long userId,
        String eventType,
        String channelType,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt) {

    /**
     * Projects a domain entity to a DTO.
     *
     * @param p the preference entity
     * @return the DTO
     */
    public static NotificationPreferenceDto from(NotificationPreference p) {
        return new NotificationPreferenceDto(
                p.getId(),
                p.getUserId(),
                p.getEventType().name(),
                p.getChannelType().name(),
                p.isEnabled(),
                p.getCreatedAt(),
                p.getUpdatedAt());
    }
}
