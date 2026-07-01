package com.kwikquant.risk.application;

import static org.assertj.core.api.Assertions.*;

import com.kwikquant.AbstractIntegrationTest;
import com.kwikquant.KwikquantApplication;
import com.kwikquant.risk.domain.RiskCheckRequest;
import com.kwikquant.risk.domain.RiskDecision;
import com.kwikquant.risk.domain.RiskPolicy;
import com.kwikquant.risk.domain.RiskRuleType;
import com.kwikquant.risk.domain.RiskVerdict;
import com.kwikquant.risk.infrastructure.RiskDecisionMapper;
import com.kwikquant.risk.infrastructure.RiskPolicyMapper;
import com.kwikquant.shared.types.OrderSide;
import com.kwikquant.shared.types.OrderType;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest(classes = KwikquantApplication.class)
@TestPropertySource(
        properties = {
            "JWT_SECRET=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
            "ENCRYPTION_KEY=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
        })
class RiskServiceTest extends AbstractIntegrationTest {

    @Autowired
    RiskService riskService;

    @Autowired
    RiskPolicyMapper policyMapper;

    @Autowired
    RiskDecisionMapper decisionMapper;

    private static long uniqueAccountId() {
        return System.nanoTime() % 10_000_000L;
    }

    private static String uniqueRequestId() {
        return "risk-req-" + System.nanoTime();
    }

    private RiskCheckRequest buildRequest(long accountId, BigDecimal notionalValue, String requestId) {
        return buildRequest(accountId, notionalValue, requestId, 0);
    }

    private RiskCheckRequest buildRequest(
            long accountId, BigDecimal notionalValue, String requestId, int recentOrderCount) {
        return new RiskCheckRequest(
                System.nanoTime() % 10_000_000L,
                accountId,
                1L,
                "BTC/USDT",
                OrderSide.BUY,
                OrderType.LIMIT,
                new BigDecimal("0.1"),
                new BigDecimal("42000"),
                notionalValue,
                recentOrderCount,
                requestId);
    }

    @Test
    void check_normalPass_approved() {
        long acct = uniqueAccountId();

        // Create a policy with a high limit that won't be exceeded
        RiskPolicy policy = new RiskPolicy();
        policy.setAccountId(acct);
        policy.setRuleType(RiskRuleType.MAX_NOTIONAL);
        policy.setName("Max Notional");
        policy.setParams(Map.of("maxNotionalUsdt", "100000"));
        policy.setEnabled(true);
        policyMapper.insert(policy);

        String reqId = uniqueRequestId();
        RiskDecision decision = riskService.check(buildRequest(acct, new BigDecimal("4200"), reqId));

        assertThat(decision).isNotNull();
        assertThat(decision.getVerdict()).isEqualTo(RiskVerdict.APPROVED);
        assertThat(decision.getRuleResults()).hasSize(1);
        assertThat(decision.getRuleResults().getFirst().passed()).isTrue();
        assertThat(decision.getId()).isNotNull();
    }

    @Test
    void check_rejection_allRulesEvaluated() {
        long acct = uniqueAccountId();

        // MAX_NOTIONAL that will fail
        RiskPolicy maxNotional = new RiskPolicy();
        maxNotional.setAccountId(acct);
        maxNotional.setRuleType(RiskRuleType.MAX_NOTIONAL);
        maxNotional.setName("Max Notional");
        maxNotional.setParams(Map.of("maxNotionalUsdt", "1000"));
        maxNotional.setEnabled(true);
        policyMapper.insert(maxNotional);

        // ORDER_FREQUENCY that will also fail: recentOrderCount=2 > maxPerMinute=1
        RiskPolicy orderFreq = new RiskPolicy();
        orderFreq.setAccountId(acct);
        orderFreq.setRuleType(RiskRuleType.ORDER_FREQUENCY);
        orderFreq.setName("Order Frequency");
        orderFreq.setParams(Map.of("maxPerMinute", "1"));
        orderFreq.setEnabled(true);
        policyMapper.insert(orderFreq);

        String reqId = uniqueRequestId();
        // notionalValue = 5000 > maxNotionalUsdt = 1000 -> MAX_NOTIONAL rejects
        // recentOrderCount = 2 > maxPerMinute = 1 -> ORDER_FREQUENCY rejects
        RiskDecision decision = riskService.check(buildRequest(acct, new BigDecimal("5000"), reqId, 2));

        assertThat(decision.getVerdict()).isEqualTo(RiskVerdict.REJECTED);
        // All rules evaluated (no short-circuit): should have 2 results
        assertThat(decision.getRuleResults()).hasSize(2);

        // Verify the MAX_NOTIONAL rule failed
        boolean maxNotionalFailed = decision.getRuleResults().stream()
                .anyMatch(r -> r.ruleType() == RiskRuleType.MAX_NOTIONAL && !r.passed());
        assertThat(maxNotionalFailed).isTrue();

        // Verify the ORDER_FREQUENCY rule also failed (no short-circuit)
        boolean orderFreqFailed = decision.getRuleResults().stream()
                .anyMatch(r -> r.ruleType() == RiskRuleType.ORDER_FREQUENCY && !r.passed());
        assertThat(orderFreqFailed).isTrue();
    }

    @Test
    void check_idempotent_returnsSameDecision() {
        long acct = uniqueAccountId();

        RiskPolicy policy = new RiskPolicy();
        policy.setAccountId(acct);
        policy.setRuleType(RiskRuleType.MAX_NOTIONAL);
        policy.setName("Max Notional");
        policy.setParams(Map.of("maxNotionalUsdt", "100000"));
        policy.setEnabled(true);
        policyMapper.insert(policy);

        String reqId = uniqueRequestId();
        RiskDecision first = riskService.check(buildRequest(acct, new BigDecimal("4200"), reqId));
        RiskDecision second = riskService.check(buildRequest(acct, new BigDecimal("4200"), reqId));

        assertThat(first.getId()).isEqualTo(second.getId());
        assertThat(first.getVerdict()).isEqualTo(second.getVerdict());
    }

    @Test
    void check_noEnabledRules_approved() {
        long acct = uniqueAccountId();
        // No policies created for this account

        String reqId = uniqueRequestId();
        RiskDecision decision = riskService.check(buildRequest(acct, new BigDecimal("4200"), reqId));

        assertThat(decision.getVerdict()).isEqualTo(RiskVerdict.APPROVED);
        assertThat(decision.getRuleResults()).isEmpty();
    }
}
