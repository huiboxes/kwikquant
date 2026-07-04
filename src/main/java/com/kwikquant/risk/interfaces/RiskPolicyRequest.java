package com.kwikquant.risk.interfaces;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;

/**
 * Request DTO for creating or updating a risk policy.
 *
 * @param accountId  exchange account id to associate this policy with
 * @param ruleType   risk rule type name (must match a {@link com.kwikquant.risk.domain.RiskRuleType} value)
 * @param name       human-readable policy name
 * @param params     rule-specific parameters (e.g. maxNotionalUsdt, maxPerMinute)
 */
public record RiskPolicyRequest(
        @Schema(description = "所属账户 ID", example = "7", requiredMode = Schema.RequiredMode.REQUIRED) long accountId,
        @Schema(description = "规则类型（枚举: MAX_NOTIONAL | ORDER_FREQUENCY | DAILY_LOSS_LIMIT）", example = "MAX_NOTIONAL", requiredMode = Schema.RequiredMode.REQUIRED)
                @NotBlank
                String ruleType,
        @Schema(description = "策略名称", example = "BTC 单笔上限", requiredMode = Schema.RequiredMode.REQUIRED)
                @NotBlank
                String name,
        @Schema(description = "规则参数，因 ruleType 而异", example = "{\"maxNotionalUsdt\":\"5000\"}", requiredMode = Schema.RequiredMode.REQUIRED)
                @NotNull
                Map<String, String> params) {}
