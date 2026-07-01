package com.kwikquant.risk.interfaces;

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
        long accountId, @NotBlank String ruleType, @NotBlank String name, @NotNull Map<String, String> params) {}
