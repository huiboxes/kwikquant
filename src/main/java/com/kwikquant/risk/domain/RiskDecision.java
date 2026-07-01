package com.kwikquant.risk.domain;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Mutable entity recording the outcome of a risk check for a single order.
 *
 * <p>Stored once per {@code requestId} (idempotent). The {@code ruleResults} list
 * captures every rule that was evaluated, regardless of pass/fail.
 */
public final class RiskDecision {

    private Long id;
    private String requestId;
    private long orderId;
    private long accountId;
    private RiskVerdict verdict;
    private List<RuleResult> ruleResults;
    private Instant createdAt;

    public RiskDecision() {}

    /**
     * Builds a human-readable summary of all rule violations.
     *
     * @return "Rule violations: reason1; reason2" or empty string if no violations
     */
    public String rejectionSummary() {
        if (ruleResults == null || ruleResults.isEmpty()) {
            return "";
        }
        // M6: expose only rule type names in the summary — this string flows into
        // RiskTriggeredEvent.reason and the WebSocket push path, so threshold values must not
        // leak. Detailed reasons remain in rule_results JSONB (GET /decisions is auth-protected).
        String violations = ruleResults.stream()
                .filter(r -> !r.passed())
                .map(r -> r.ruleType().name())
                .collect(Collectors.joining("; "));
        if (violations.isEmpty()) {
            return "";
        }
        return "Rule violations: " + violations;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public long getOrderId() {
        return orderId;
    }

    public void setOrderId(long orderId) {
        this.orderId = orderId;
    }

    public long getAccountId() {
        return accountId;
    }

    public void setAccountId(long accountId) {
        this.accountId = accountId;
    }

    public RiskVerdict getVerdict() {
        return verdict;
    }

    public void setVerdict(RiskVerdict verdict) {
        this.verdict = verdict;
    }

    public List<RuleResult> getRuleResults() {
        return ruleResults;
    }

    public void setRuleResults(List<RuleResult> ruleResults) {
        this.ruleResults = ruleResults;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
