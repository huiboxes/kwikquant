package com.kwikquant.risk.interfaces;

import com.kwikquant.risk.domain.RiskPolicy;
import java.time.Instant;
import java.util.Map;

/**
 * Response DTO projecting a {@link RiskPolicy} for the REST API.
 *
 * @param id         policy id
 * @param accountId  owning exchange account id
 * @param ruleType   rule type name
 * @param name       human-readable name
 * @param params     rule-specific parameters
 * @param enabled    whether the policy is currently active
 * @param createdAt  creation timestamp
 * @param updatedAt  last update timestamp
 */
public record RiskPolicyDto(
        long id,
        long accountId,
        String ruleType,
        String name,
        Map<String, String> params,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt) {

    /**
     * Projects a domain entity to a DTO.
     *
     * @param p the risk policy entity
     * @return the DTO
     */
    public static RiskPolicyDto from(RiskPolicy p) {
        return new RiskPolicyDto(
                p.getId(),
                p.getAccountId(),
                p.getRuleType().name(),
                p.getName(),
                p.getParams(),
                p.isEnabled(),
                p.getCreatedAt(),
                p.getUpdatedAt());
    }
}
