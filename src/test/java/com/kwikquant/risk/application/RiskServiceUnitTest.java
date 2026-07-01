package com.kwikquant.risk.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.kwikquant.risk.domain.RiskCheckRequest;
import com.kwikquant.risk.domain.RiskDecision;
import com.kwikquant.risk.domain.RiskPolicy;
import com.kwikquant.risk.domain.RiskRuleType;
import com.kwikquant.risk.domain.RiskVerdict;
import com.kwikquant.risk.domain.RuleEvaluator;
import com.kwikquant.risk.domain.RuleResult;
import com.kwikquant.risk.infrastructure.RiskDecisionMapper;
import com.kwikquant.risk.infrastructure.RiskPolicyMapper;
import com.kwikquant.shared.types.OrderSide;
import com.kwikquant.shared.types.OrderType;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

/**
 * Pure-Mockito unit tests for {@link RiskService} covering the defensive paths that the
 * integration tests (against PostgreSQL) cannot easily reach:
 *
 * <ul>
 *   <li>P2-4a: an enabled policy whose ruleType has no registered evaluator → fail-closed
 *       (passed=false). {@code create()} rejects unsupported rule types, so this path is
 *       unreachable in normal operation; the fail-closed default is the defensive contract.
 *   <li>P2-4b: a registered evaluator that throws {@link RuntimeException} → catch-all returns
 *       {@code RuleResult(passed=false, "internal evaluator error")}.
 *   <li>P2-4c: {@code decisionMapper.insert} racing on the requestId unique key →
 *       {@link DuplicateKeyException} is caught and the winner's decision is returned via
 *       {@code findByRequestId}.
 * </ul>
 *
 * <p>No Spring context / DB: the service is constructed directly with Mockito mocks, so the
 * tests are deterministic and fast.
 */
class RiskServiceUnitTest {

    private static RiskCheckRequest request() {
        return new RiskCheckRequest(
                100L,
                1L,
                1L,
                "BTC/USDT",
                OrderSide.BUY,
                OrderType.LIMIT,
                new BigDecimal("0.1"),
                new BigDecimal("42000"),
                new BigDecimal("4200"),
                0,
                BigDecimal.ZERO,
                "risk-req-unit");
    }

    private static RiskPolicy policy(RiskRuleType ruleType) {
        RiskPolicy policy = new RiskPolicy();
        policy.setId(1L);
        policy.setAccountId(1L);
        policy.setRuleType(ruleType);
        policy.setName(ruleType.name());
        policy.setEnabled(true);
        return policy;
    }

    @Test
    void check_whenNoEvaluatorRegisteredForRuleType_failClosedRejects() {
        // Evaluator supports MAX_NOTIONAL, but the enabled policy is ORDER_FREQUENCY →
        // evaluatorMap.get(ORDER_FREQUENCY) == null → fail-closed path.
        RuleEvaluator maxNotionalEvaluator = mock(RuleEvaluator.class);
        when(maxNotionalEvaluator.supportedType()).thenReturn(RiskRuleType.MAX_NOTIONAL);

        RiskPolicyMapper policyMapper = mock(RiskPolicyMapper.class);
        when(policyMapper.findEnabledByAccountId(1L)).thenReturn(List.of(policy(RiskRuleType.ORDER_FREQUENCY)));

        RiskDecisionMapper decisionMapper = mock(RiskDecisionMapper.class);
        when(decisionMapper.findByRequestId("risk-req-unit")).thenReturn(null);

        RiskService service = new RiskService(policyMapper, decisionMapper, List.of(maxNotionalEvaluator));

        RiskDecision decision = service.check(request());

        assertThat(decision.getVerdict()).isEqualTo(RiskVerdict.REJECTED);
        assertThat(decision.getRuleResults()).hasSize(1);
        RuleResult result = decision.getRuleResults().getFirst();
        assertThat(result.ruleType()).isEqualTo(RiskRuleType.ORDER_FREQUENCY);
        assertThat(result.passed()).isFalse();
        assertThat(result.reason()).contains("no evaluator registered for ORDER_FREQUENCY");
        // The defensive path must not invoke the mismatched evaluator
        verify(maxNotionalEvaluator, never()).evaluate(any(), any());
        verify(decisionMapper).insert(any(RiskDecision.class));
    }

    @Test
    void check_whenEvaluatorThrowsException_catchAllReturnsInternalError() {
        RuleEvaluator evaluator = mock(RuleEvaluator.class);
        when(evaluator.supportedType()).thenReturn(RiskRuleType.MAX_NOTIONAL);
        // P2-4b: evaluator contract violation — it must not throw, but RiskService defends
        // against it with a catch-all so a single buggy rule cannot abort the chain.
        when(evaluator.evaluate(any(), any())).thenThrow(new RuntimeException("boom"));

        RiskPolicyMapper policyMapper = mock(RiskPolicyMapper.class);
        when(policyMapper.findEnabledByAccountId(1L)).thenReturn(List.of(policy(RiskRuleType.MAX_NOTIONAL)));

        RiskDecisionMapper decisionMapper = mock(RiskDecisionMapper.class);
        when(decisionMapper.findByRequestId("risk-req-unit")).thenReturn(null);

        RiskService service = new RiskService(policyMapper, decisionMapper, List.of(evaluator));

        RiskDecision decision = service.check(request());

        assertThat(decision.getVerdict()).isEqualTo(RiskVerdict.REJECTED);
        assertThat(decision.getRuleResults()).hasSize(1);
        RuleResult result = decision.getRuleResults().getFirst();
        assertThat(result.ruleType()).isEqualTo(RiskRuleType.MAX_NOTIONAL);
        assertThat(result.passed()).isFalse();
        assertThat(result.reason()).isEqualTo("internal evaluator error");
    }

    @Test
    void check_whenInsertThrowsDuplicateKey_returnsExistingDecision() {
        // P2-4c: concurrent insert race. findByRequestId returns null first (no prior decision),
        // insert throws DuplicateKeyException (the racing writer won the unique key), then
        // findByRequestId returns the winner's decision on the second call.
        RiskDecision existing = new RiskDecision();
        existing.setRequestId("risk-req-unit");
        existing.setVerdict(RiskVerdict.APPROVED);

        RiskPolicyMapper policyMapper = mock(RiskPolicyMapper.class);
        when(policyMapper.findEnabledByAccountId(1L)).thenReturn(List.of());

        RiskDecisionMapper decisionMapper = mock(RiskDecisionMapper.class);
        when(decisionMapper.findByRequestId("risk-req-unit"))
                .thenReturn(null) // first call: no existing decision
                .thenReturn(existing); // second call: the racing writer's decision
        doThrow(new DuplicateKeyException("requestId unique constraint"))
                .when(decisionMapper)
                .insert(any(RiskDecision.class));

        RiskService service = new RiskService(policyMapper, decisionMapper, List.of());

        RiskDecision decision = service.check(request());

        // The winner's decision is returned as-is, never null
        assertThat(decision).isSameAs(existing);
        assertThat(decision.getVerdict()).isEqualTo(RiskVerdict.APPROVED);
        // findByRequestId must be called twice: idempotent probe + race-recovery fetch
        verify(decisionMapper, times(2)).findByRequestId("risk-req-unit");
        verify(decisionMapper).insert(any(RiskDecision.class));
    }
}
