package com.kwikquant.risk.domain;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class RiskDomainTest {

    @Test
    void rejectionSummary_multipleViolations() {
        RiskDecision decision = new RiskDecision();
        decision.setRuleResults(List.of(
                new RuleResult(RiskRuleType.MAX_NOTIONAL, false, "notional 60000 USDT exceeds max 50000 USDT"),
                new RuleResult(RiskRuleType.DAILY_LOSS_LIMIT, true, null),
                new RuleResult(RiskRuleType.ORDER_FREQUENCY, false, "frequency 120/min exceeds max 60/min")));

        String summary = decision.rejectionSummary();

        // M6: summary contains only rule type names, not threshold values
        assertThat(summary).startsWith("Rule violations: ");
        assertThat(summary).contains("MAX_NOTIONAL");
        assertThat(summary).contains("ORDER_FREQUENCY");
        // Threshold values must NOT leak into the summary (WebSocket push path)
        assertThat(summary).doesNotContain("60000");
        assertThat(summary).doesNotContain("50000");
        assertThat(summary).doesNotContain("120");
        assertThat(summary).doesNotContain("60");
        // The passed rule's type should NOT be in the summary
        assertThat(summary).doesNotContain("DAILY_LOSS_LIMIT");
    }

    @Test
    void rejectionSummary_allPassed() {
        RiskDecision decision = new RiskDecision();
        decision.setRuleResults(List.of(
                new RuleResult(RiskRuleType.MAX_NOTIONAL, true, null),
                new RuleResult(RiskRuleType.DAILY_LOSS_LIMIT, true, null)));

        assertThat(decision.rejectionSummary()).isEmpty();
    }

    @Test
    void rejectionSummary_emptyResults() {
        RiskDecision decision = new RiskDecision();
        decision.setRuleResults(List.of());

        assertThat(decision.rejectionSummary()).isEmpty();
    }

    @Test
    void rejectionSummary_nullResults() {
        RiskDecision decision = new RiskDecision();
        decision.setRuleResults(null);

        assertThat(decision.rejectionSummary()).isEmpty();
    }

    @Test
    void riskRejectedException_message() {
        RiskRejectedException ex = new RiskRejectedException(42L, "notional too high");

        assertThat(ex.getMessage()).isEqualTo("Order 42 rejected: notional too high");
        assertThat(ex.getOrderId()).isEqualTo(42L);
        assertThat(ex.getReason()).isEqualTo("notional too high");
    }

    @Test
    void riskPolicyNotFoundException_message() {
        RiskPolicyNotFoundException ex = new RiskPolicyNotFoundException(99L);

        assertThat(ex.getMessage()).isEqualTo("Risk policy not found: 99");
        assertThat(ex.getPolicyId()).isEqualTo(99L);
    }

    @Test
    void riskRuleType_values() {
        assertThat(RiskRuleType.values())
                .containsExactly(
                        RiskRuleType.MAX_NOTIONAL, RiskRuleType.DAILY_LOSS_LIMIT, RiskRuleType.ORDER_FREQUENCY);
    }

    @Test
    void riskVerdict_values() {
        assertThat(RiskVerdict.values()).containsExactly(RiskVerdict.APPROVED, RiskVerdict.REJECTED);
    }
}
