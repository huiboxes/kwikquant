package com.kwikquant.risk.domain.evaluators;

import static org.assertj.core.api.Assertions.*;

import com.kwikquant.risk.domain.RiskCheckRequest;
import com.kwikquant.risk.domain.RiskPolicy;
import com.kwikquant.risk.domain.RiskRuleType;
import com.kwikquant.risk.domain.RuleResult;
import com.kwikquant.shared.types.MarketType;
import com.kwikquant.shared.types.OrderSide;
import com.kwikquant.shared.types.OrderType;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MaxNotionalEvaluatorTest {

    private final MaxNotionalEvaluator evaluator = new MaxNotionalEvaluator();

    @Test
    void supportedType() {
        assertThat(evaluator.supportedType()).isEqualTo(RiskRuleType.MAX_NOTIONAL);
    }

    @Test
    void withinLimit_passes() {
        RiskPolicy policy = policyWithMax("50000");
        RiskCheckRequest request = requestWithNotional(new BigDecimal("4200"));

        RuleResult result = evaluator.evaluate(policy, request);

        assertThat(result.passed()).isTrue();
        assertThat(result.reason()).isNull();
        assertThat(result.ruleType()).isEqualTo(RiskRuleType.MAX_NOTIONAL);
    }

    @Test
    void exceedsLimit_fails() {
        RiskPolicy policy = policyWithMax("50000");
        RiskCheckRequest request = requestWithNotional(new BigDecimal("60000"));

        RuleResult result = evaluator.evaluate(policy, request);

        assertThat(result.passed()).isFalse();
        assertThat(result.reason()).contains("60000");
        assertThat(result.reason()).contains("50000");
        assertThat(result.reason()).contains("exceeds max");
    }

    @Test
    void exactlyAtLimit_passes() {
        RiskPolicy policy = policyWithMax("50000.00");
        RiskCheckRequest request = requestWithNotional(new BigDecimal("50000.00"));

        RuleResult result = evaluator.evaluate(policy, request);

        assertThat(result.passed()).isTrue();
    }

    @Test
    void nullNotionalValue_fails() {
        RiskPolicy policy = policyWithMax("50000");
        RiskCheckRequest request = requestWithNotional(null);

        RuleResult result = evaluator.evaluate(policy, request);

        assertThat(result.passed()).isFalse();
        assertThat(result.reason()).isEqualTo("notional value unavailable");
    }

    @Test
    void invalidParamValue_returnsInternalError() {
        RiskPolicy policy = new RiskPolicy();
        policy.setParams(Map.of("maxNotionalUsdt", "not-a-number"));
        RiskCheckRequest request = requestWithNotional(new BigDecimal("1000"));

        RuleResult result = evaluator.evaluate(policy, request);

        assertThat(result.passed()).isFalse();
        assertThat(result.reason()).isEqualTo("internal evaluator error");
    }

    private RiskPolicy policyWithMax(String max) {
        RiskPolicy policy = new RiskPolicy();
        policy.setRuleType(RiskRuleType.MAX_NOTIONAL);
        policy.setParams(Map.of("maxNotionalUsdt", max));
        return policy;
    }

    private RiskCheckRequest requestWithNotional(BigDecimal notionalValue) {
        return requestWithNotional(notionalValue, MarketType.SPOT);
    }

    private RiskCheckRequest requestWithNotional(BigDecimal notionalValue, MarketType marketType) {
        return new RiskCheckRequest(
                1L,
                1L,
                1L,
                "BTC/USDT",
                OrderSide.BUY,
                OrderType.LIMIT,
                new BigDecimal("0.1"),
                new BigDecimal("42000"),
                notionalValue,
                0,
                BigDecimal.ZERO,
                marketType,
                null,
                null, null,
                "req-1");
    }

    /** 阶段2h(§11 M8-new):PERP 跳过 MaxNotional(交 MaxInitialMarginEvaluator),高 notional 也 passed=true。 */
    @Test
    void perpMarketType_skipsMaxNotional() {
        RiskPolicy policy = policyWithMax("50000");
        // notional 60000 > max 50000,但 PERP 跳过 → passed=true
        RiskCheckRequest request = requestWithNotional(new BigDecimal("60000"), MarketType.PERP);

        RuleResult result = evaluator.evaluate(policy, request);

        assertThat(result.passed()).isTrue();
        assertThat(result.reason()).contains("PERP skipped");
    }
}
