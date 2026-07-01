package com.kwikquant.notification.interfaces;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/**
 * Request DTO for batch-upserting notification preferences.
 *
 * <p>Request body shape (per tech-design §4.3 PUT):
 * <pre>{@code
 * { "preferences": [ {"eventType":"RISK_REJECTED","channelType":"WEBSOCKET","enabled":true} ] }
 * }</pre>
 *
 * @param preferences list of preference items to upsert (must be non-empty)
 */
public record NotificationPreferenceRequest(@NotEmpty @Valid List<PreferenceItem> preferences) {

    /**
     * A single preference update item.
     *
     * @param eventType   notification event type name (must match a
     *                    {@link com.kwikquant.notification.domain.NotificationEventType} value)
     * @param channelType delivery channel type name (must match a
     *                    {@link com.kwikquant.notification.domain.NotificationChannelType} value)
     * @param enabled     whether this combination is enabled
     */
    public record PreferenceItem(
            @jakarta.validation.constraints.NotBlank String eventType,
            @jakarta.validation.constraints.NotBlank String channelType,
            boolean enabled) {}
}
