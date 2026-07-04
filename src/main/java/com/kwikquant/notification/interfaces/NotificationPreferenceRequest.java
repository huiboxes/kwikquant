package com.kwikquant.notification.interfaces;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/** 通知偏好批量更新请求体。 */
public record NotificationPreferenceRequest(
        @Schema(description = "偏好项列表，至少 1 条") @NotEmpty @Valid List<PreferenceItem> preferences) {

    /** 单条偏好更新项。 */
    public record PreferenceItem(
            @Schema(
                    description =
                            "事件类型（枚举: RISK_REJECTED | ORDER_FILLED | ORDER_CANCELLED | "
                                    + "STRATEGY_STARTED | STRATEGY_STOPPED | STRATEGY_ERROR）",
                    example = "RISK_REJECTED")
                    @jakarta.validation.constraints.NotBlank
                    String eventType,
            @Schema(description = "渠道类型（枚举: WEBSOCKET | EMAIL 等）", example = "WEBSOCKET")
                    @jakarta.validation.constraints.NotBlank
                    String channelType,
            @Schema(description = "是否启用", example = "true") boolean enabled) {}
}
