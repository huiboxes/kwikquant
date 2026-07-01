package com.kwikquant.notification.application;

import com.kwikquant.notification.domain.NotificationChannelType;
import com.kwikquant.notification.domain.NotificationEventType;
import com.kwikquant.notification.domain.NotificationPreference;
import com.kwikquant.notification.infrastructure.NotificationPreferenceMapper;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Manages user notification preferences (CRUD).
 */
@Service
public class NotificationPreferenceService {

    private static final Logger log = LoggerFactory.getLogger(NotificationPreferenceService.class);

    private final NotificationPreferenceMapper preferenceMapper;

    public NotificationPreferenceService(NotificationPreferenceMapper preferenceMapper) {
        this.preferenceMapper = preferenceMapper;
    }

    /**
     * Lists all notification preferences for a user.
     *
     * @param userId the user id
     * @return list of preferences
     */
    public List<NotificationPreference> listByUser(long userId) {
        return preferenceMapper.findByUserId(userId);
    }

    /**
     * Upserts a batch of notification preferences for a user.
     *
     * <p>For each item, builds a {@link NotificationPreference} and performs an
     * INSERT ON CONFLICT UPDATE to ensure idempotent writes.
     *
     * @param userId the user id
     * @param items  list of preference update items
     */
    public void upsertPreferences(long userId, List<PreferenceUpdateItem> items) {
        for (PreferenceUpdateItem item : items) {
            NotificationPreference pref = new NotificationPreference();
            pref.setUserId(userId);
            pref.setEventType(item.eventType());
            pref.setChannelType(item.channelType());
            pref.setEnabled(item.enabled());
            preferenceMapper.upsert(pref);
        }
        log.info("[notification] upserted {} preferences for userId={}", items.size(), userId);
    }

    /**
     * Data carrier for a single preference update.
     *
     * @param eventType   the notification event type
     * @param channelType the delivery channel type
     * @param enabled     whether this combination is enabled
     */
    public record PreferenceUpdateItem(
            NotificationEventType eventType, NotificationChannelType channelType, boolean enabled) {}
}
