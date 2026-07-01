package com.kwikquant.notification.domain;

import java.time.Instant;

/**
 * Mutable entity representing a user's notification preference for a specific
 * event type and delivery channel combination.
 *
 * <p>Persisted in the {@code notification_preferences} table. Uses traditional
 * getter/setter style consistent with other project entities.
 */
public class NotificationPreference {

    private Long id;
    private long userId;
    private NotificationEventType eventType;
    private NotificationChannelType channelType;
    private boolean enabled;
    private Instant createdAt;
    private Instant updatedAt;

    public NotificationPreference() {}

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public long getUserId() {
        return userId;
    }

    public void setUserId(long userId) {
        this.userId = userId;
    }

    public NotificationEventType getEventType() {
        return eventType;
    }

    public void setEventType(NotificationEventType eventType) {
        this.eventType = eventType;
    }

    public NotificationChannelType getChannelType() {
        return channelType;
    }

    public void setChannelType(NotificationChannelType channelType) {
        this.channelType = channelType;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
