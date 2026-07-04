package com.kwikquant.risk.interfaces;

import com.kwikquant.risk.domain.RiskDecision;
import com.kwikquant.risk.domain.RuleResult;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/**
 * Response DTO projecting a {@link RiskDecision} for the REST API.
 *
 * @param id          decision id
 * @param orderId     order id this decision belongs to
 * @param accountId   exchange account id
 * @param verdict     APPROVED or REJECTED
 * @param ruleResults per-rule evaluation results
 * @param requestId   idempotency key
 * @param createdAt   decision timestamp
 */
public record RiskDecisionDto(
        @Schema(description = "决策 ID", example = "512") long id,
        @Schema(description = "订单 ID", example = "1024") long orderId,
        @Schema(description = "账户 ID", example = "7") long accountId,
        @Schema(description = "决策结果（枚举: APPROVED | REJECTED）", example = "REJECTED") String verdict,
        @Schema(description = "逐条规则评估结果") List<RuleResultDto> ruleResults,
        @Schema(description = "幂等键") String requestId,
        @Schema(description = "决策时间", example = "2026-07-04T12:00:00Z") Instant createdAt) {

    /**
     * Projects a domain entity to a DTO.
     *
     * @param d the risk decision entity
     * @return the DTO
     */
    public static RiskDecisionDto from(RiskDecision d) {
        List<RuleResultDto> results = d.getRuleResults() != null
                ? d.getRuleResults().stream().map(RuleResultDto::from).toList()
                : List.of();
        return new RiskDecisionDto(
                d.getId(),
                d.getOrderId(),
                d.getAccountId(),
                d.getVerdict().name(),
                results,
                d.getRequestId(),
                d.getCreatedAt());
    }

    /**
     * Per-rule evaluation result DTO.
     *
     * @param ruleType rule type name
     * @param passed   whether the rule passed
     * @param reason   failure reason (null when passed)
     */
    public record RuleResultDto(
            @Schema(description = "规则类型（枚举: MAX_NOTIONAL | ORDER_FREQUENCY | DAILY_LOSS_LIMIT）", example = "MAX_NOTIONAL")
                    String ruleType,
            @Schema(description = "是否通过", example = "false") boolean passed,
            @Schema(description = "失败原因，通过时为 null", example = "notional 6000 exceeds max 5000") String reason) {

        /**
         * Projects a domain rule result to a DTO.
         *
         * @param r the rule result
         * @return the DTO
         */
        public static RuleResultDto from(RuleResult r) {
            return new RuleResultDto(r.ruleType().name(), r.passed(), r.reason());
        }
    }
}
