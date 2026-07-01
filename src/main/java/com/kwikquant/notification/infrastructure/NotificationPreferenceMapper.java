package com.kwikquant.notification.infrastructure;

import com.kwikquant.notification.domain.NotificationPreference;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * MyBatis mapper for the {@code notification_preferences} table.
 */
@Mapper
public interface NotificationPreferenceMapper {

    /**
     * Finds all notification preferences for a user.
     *
     * @param userId the user id
     * @return list of preferences
     */
    @Select(
            """
            SELECT id, user_id, event_type, channel_type, enabled, created_at, updated_at
            FROM notification_preferences WHERE user_id = #{userId}
            """)
    List<NotificationPreference> findByUserId(long userId);

    /**
     * Finds enabled preferences for a user and event type.
     *
     * @param userId    the user id
     * @param eventType the event type name (enum string)
     * @return list of enabled preferences matching the criteria
     */
    @Select(
            """
            SELECT id, user_id, event_type, channel_type, enabled, created_at, updated_at
            FROM notification_preferences
            WHERE user_id = #{userId} AND event_type = #{eventType} AND enabled = true
            """)
    List<NotificationPreference> findEnabledByUserAndEventType(long userId, String eventType);

    /**
     * Finds all preferences (enabled and disabled) for a user and event type.
     *
     * <p>Used to distinguish "no preference configured" (apply WEBSOCKET default) from
     * "preference exists but disabled" (do not push).
     */
    @Select(
            """
            SELECT id, user_id, event_type, channel_type, enabled, created_at, updated_at
            FROM notification_preferences
            WHERE user_id = #{userId} AND event_type = #{eventType}
            """)
    List<NotificationPreference> findByUserIdAndEventType(long userId, String eventType);

    /**
     * Inserts a preference or updates the enabled flag on conflict.
     *
     * @param pref the preference to upsert
     */
    @Insert(
            """
            INSERT INTO notification_preferences (user_id, event_type, channel_type, enabled)
            VALUES (#{userId}, #{eventType}, #{channelType}, #{enabled})
            ON CONFLICT (user_id, event_type, channel_type)
            DO UPDATE SET enabled = EXCLUDED.enabled, updated_at = now()
            """)
    void upsert(NotificationPreference pref);
}
