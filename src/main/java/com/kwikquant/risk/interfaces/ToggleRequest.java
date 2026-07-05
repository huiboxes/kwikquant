package com.kwikquant.risk.interfaces;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Request DTO for toggling a risk policy's enabled state.
 *
 * @param enabled whether the policy should be enabled or disabled
 */
public record ToggleRequest(
        @Schema(description = "是否启用，false 表示策略存在但不生效", example = "true", requiredMode = Schema.RequiredMode.REQUIRED)
                boolean enabled) {}
