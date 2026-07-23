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
import com.kwikquant.risk.domain.evaluators.MaxInitialMarginEvaluator;
import com.kwikquant.risk.infrastructure.RiskDecisionMapper;
import com.kwikquant.risk.infrastructure.RiskPolicyMapper;
import com.kwikquant.shared.types.MarketType;
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
                MarketType.SPOT,
                null,
                null, null,
                "risk-req-unit");
    }

    /** PERP 请求 + 指定 availableMargin(阶段2h 兜底测试用)。 */
    private static RiskCheckRequest perpRequest(BigDecimal notional, int leverage, BigDecimal availableMargin) {
        return new RiskCheckRequest(
                100L,
                1L,
                1L,
                "BTC/USDT",
                OrderSide.BUY,
                OrderType.MARKET,
                new BigDecimal("0.1"),
                null,
                notional,
                0,
                BigDecimal.ZERO,
                MarketType.PERP,
                leverage,
                availableMargin, availableMargin,
                "risk-req-perp");
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

    /**
     * 阶段2h(§10 M15)兜底:PERP 请求 + 账户无 MAX_INITIAL_MARGIN policy → RiskService 用默认 80%
     * (§12 m1-s)评一次(fail-closed,不 auto-approve PERP)。availableMargin 足够 → APPROVED。
     *
     * <p>per-account risk_policies 表无法全局 seed 默认 policy,故用隐式默认 ratio 兜底,
     * 等价"每账户隐式 80% policy"。真正的 per-account seed 留账账户生命周期阶段。
     */
    @Test
    void evaluate_perpRequestNoPolicy_fallback80ApprovesWhenMarginSufficient() {
        RuleEvaluator evaluator = new MaxInitialMarginEvaluator();
        RiskPolicyMapper policyMapper = mock(RiskPolicyMapper.class);
        when(policyMapper.findEnabledByAccountId(1L)).thenReturn(List.of());
        RiskDecisionMapper decisionMapper = mock(RiskDecisionMapper.class);

        RiskService service = new RiskService(policyMapper, decisionMapper, List.of(evaluator));
        // notional 4200 / leverage 10 = initialMargin 420; availableMargin 1000 × 0.8 = 800; 420 <= 800 → passed
        RiskDecision decision = service.evaluate(perpRequest(new BigDecimal("4200"), 10, new BigDecimal("1000")));

        assertThat(decision.getVerdict()).isEqualTo(RiskVerdict.APPROVED);
        assertThat(decision.getRuleResults()).hasSize(1);
        RuleResult result = decision.getRuleResults().getFirst();
        assertThat(result.ruleType()).isEqualTo(RiskRuleType.MAX_INITIAL_MARGIN);
        assertThat(result.passed()).isTrue();
    }

    @Test
    void evaluate_perpRequestNoPolicy_fallback80RejectsWhenMarginInsufficient() {
        RuleEvaluator evaluator = new MaxInitialMarginEvaluator();
        RiskPolicyMapper policyMapper = mock(RiskPolicyMapper.class);
        when(policyMapper.findEnabledByAccountId(1L)).thenReturn(List.of());
        RiskDecisionMapper decisionMapper = mock(RiskDecisionMapper.class);

        RiskService service = new RiskService(policyMapper, decisionMapper, List.of(evaluator));
        // notional 42000 / leverage 10 = initialMargin 4200; availableMargin 1000 × 0.8 = 800; 4200 > 800 → rejected
        RiskDecision decision = service.evaluate(perpRequest(new BigDecimal("42000"), 10, new BigDecimal("1000")));

        assertThat(decision.getVerdict()).isEqualTo(RiskVerdict.REJECTED);
        assertThat(decision.getRuleResults()).hasSize(1);
        assertThat(decision.getRuleResults().getFirst().passed()).isFalse();
    }
}
