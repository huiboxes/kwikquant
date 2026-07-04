package com.kwikquant.notification.interfaces;

import com.kwikquant.notification.domain.NotificationPreference;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/** 通知偏好响应 DTO。 */
public record NotificationPreferenceDto(
        @Schema(description = "偏好 ID", example = "1") long id,
        @Schema(description = "所属用户 ID", example = "42") long userId,
        @Schema(description = "事件类型", example = "RISK_REJECTED") String eventType,
        @Schema(description = "渠道类型", example = "WEBSOCKET") String channelType,
        @Schema(description = "是否启用", example = "true") boolean enabled,
        @Schema(description = "创建时间") Instant createdAt,
        @Schema(description = "最后更新时间") Instant updatedAt) {

    /** Projects a domain entity to a DTO. */
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
