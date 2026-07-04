package com.kwikquant.risk.interfaces;

import com.kwikquant.risk.domain.RiskPolicy;
import io.swagger.v3.oas.annotations.media.Schema;
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
        @Schema(description = "策略 ID", example = "42") long id,
        @Schema(description = "所属账户 ID", example = "7") long accountId,
        @Schema(description = "规则类型（枚举: MAX_NOTIONAL | ORDER_FREQUENCY | DAILY_LOSS_LIMIT）", example = "MAX_NOTIONAL")
                String ruleType,
        @Schema(description = "策略名称", example = "BTC 单笔上限") String name,
        @Schema(description = "规则参数键值对，因 ruleType 而异（如 maxNotionalUsdt/maxPerMinute/maxLossUsdt）", example = "{\"maxNotionalUsdt\":\"5000\"}")
                Map<String, String> params,
        @Schema(description = "是否启用，false 表示策略存在但不生效", example = "true") boolean enabled,
        @Schema(description = "创建时间", example = "2026-07-04T12:00:00Z") Instant createdAt,
        @Schema(description = "最后更新时间", example = "2026-07-04T12:00:00Z") Instant updatedAt) {

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
