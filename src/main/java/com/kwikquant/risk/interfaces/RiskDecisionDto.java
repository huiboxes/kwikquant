package com.kwikquant.risk.interfaces;

import com.kwikquant.risk.domain.RiskDecision;
import com.kwikquant.risk.domain.RuleResult;
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
        long id,
        long orderId,
        long accountId,
        String verdict,
        List<RuleResultDto> ruleResults,
        String requestId,
        Instant createdAt) {

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
    public record RuleResultDto(String ruleType, boolean passed, String reason) {

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
