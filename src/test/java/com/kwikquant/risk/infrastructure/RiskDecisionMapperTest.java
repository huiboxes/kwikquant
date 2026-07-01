package com.kwikquant.risk.infrastructure;

import static org.assertj.core.api.Assertions.*;

import com.kwikquant.AbstractIntegrationTest;
import com.kwikquant.KwikquantApplication;
import com.kwikquant.risk.domain.RiskDecision;
import com.kwikquant.risk.domain.RiskRuleType;
import com.kwikquant.risk.domain.RiskVerdict;
import com.kwikquant.risk.domain.RuleResult;
import java.util.List;
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
class RiskDecisionMapperTest extends AbstractIntegrationTest {

    @Autowired
    RiskDecisionMapper decisionMapper;

    private static String uniqueRequestId() {
        return "req-" + System.nanoTime();
    }

    @Test
    void insertAndFindByRequestId() {
        String reqId = uniqueRequestId();
        RiskDecision decision = new RiskDecision();
        decision.setRequestId(reqId);
        decision.setOrderId(100L);
        decision.setAccountId(1L);
        decision.setVerdict(RiskVerdict.APPROVED);
        decision.setRuleResults(
                List.of(new RuleResult(RiskRuleType.MAX_NOTIONAL, true, null)));
        decisionMapper.insert(decision);

        assertThat(decision.getId()).isNotNull();

        RiskDecision loaded = decisionMapper.findByRequestId(reqId);
        assertThat(loaded).isNotNull();
        assertThat(loaded.getOrderId()).isEqualTo(100L);
        assertThat(loaded.getAccountId()).isEqualTo(1L);
        assertThat(loaded.getVerdict()).isEqualTo(RiskVerdict.APPROVED);
        assertThat(loaded.getRuleResults()).hasSize(1);
        assertThat(loaded.getRuleResults().getFirst().ruleType()).isEqualTo(RiskRuleType.MAX_NOTIONAL);
        assertThat(loaded.getRuleResults().getFirst().passed()).isTrue();
        assertThat(loaded.getCreatedAt()).isNotNull();
    }

    @Test
    void findByOrderId() {
        String reqId = uniqueRequestId();
        long orderId = System.nanoTime() % 10_000_000L;

        RiskDecision decision = new RiskDecision();
        decision.setRequestId(reqId);
        decision.setOrderId(orderId);
        decision.setAccountId(2L);
        decision.setVerdict(RiskVerdict.REJECTED);
        decision.setRuleResults(List.of(
                new RuleResult(RiskRuleType.MAX_NOTIONAL, false, "notional 60000 USDT exceeds max 50000 USDT"),
                new RuleResult(RiskRuleType.DAILY_LOSS_LIMIT, true, null)));
        decisionMapper.insert(decision);

        RiskDecision loaded = decisionMapper.findByOrderId(orderId);
        assertThat(loaded).isNotNull();
        assertThat(loaded.getRequestId()).isEqualTo(reqId);
        assertThat(loaded.getVerdict()).isEqualTo(RiskVerdict.REJECTED);
        assertThat(loaded.getRuleResults()).hasSize(2);

        // Verify failed rule
        RuleResult failedRule = loaded.getRuleResults().stream()
                .filter(r -> !r.passed())
                .findFirst()
                .orElseThrow();
        assertThat(failedRule.ruleType()).isEqualTo(RiskRuleType.MAX_NOTIONAL);
        assertThat(failedRule.reason()).contains("exceeds max");
    }

    @Test
    void findByRequestId_returnsNull_whenNotFound() {
        assertThat(decisionMapper.findByRequestId("nonexistent-" + System.nanoTime())).isNull();
    }

    @Test
    void emptyRuleResults_serialization() {
        String reqId = uniqueRequestId();
        RiskDecision decision = new RiskDecision();
        decision.setRequestId(reqId);
        decision.setOrderId(200L);
        decision.setAccountId(3L);
        decision.setVerdict(RiskVerdict.APPROVED);
        decision.setRuleResults(List.of());
        decisionMapper.insert(decision);

        RiskDecision loaded = decisionMapper.findByRequestId(reqId);
        assertThat(loaded).isNotNull();
        assertThat(loaded.getRuleResults()).isEmpty();
    }
}
