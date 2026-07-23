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

class OrderFrequencyEvaluatorTest {

    private final OrderFrequencyEvaluator evaluator = new OrderFrequencyEvaluator();

    @Test
    void supportedType() {
        assertThat(evaluator.supportedType()).isEqualTo(RiskRuleType.ORDER_FREQUENCY);
    }

    @Test
    void withinLimit_passes() {
        RiskPolicy policy = policyWithMax("60");
        RiskCheckRequest request = requestWithCount(30);

        RuleResult result = evaluator.evaluate(policy, request);

        assertThat(result.passed()).isTrue();
        assertThat(result.reason()).isNull();
        assertThat(result.ruleType()).isEqualTo(RiskRuleType.ORDER_FREQUENCY);
    }

    @Test
    void exactlyAtLimit_passes() {
        // maxPerMinute=60, recentOrderCount=60 -> 60 > 60 is false -> pass
        RiskPolicy policy = policyWithMax("60");
        RiskCheckRequest request = requestWithCount(60);

        assertThat(evaluator.evaluate(policy, request).passed()).isTrue();
    }

    @Test
    void exceedsLimit_fails() {
        RiskPolicy policy = policyWithMax("60");
        RiskCheckRequest request = requestWithCount(61);

        RuleResult result = evaluator.evaluate(policy, request);

        assertThat(result.passed()).isFalse();
        assertThat(result.reason()).contains("61");
        assertThat(result.reason()).contains("60");
        assertThat(result.reason()).contains("exceeds max");
    }

    @Test
    void invalidParamValue_returnsInternalError() {
        RiskPolicy policy = new RiskPolicy();
        policy.setRuleType(RiskRuleType.ORDER_FREQUENCY);
        policy.setParams(Map.of("maxPerMinute", "not-a-number"));
        RiskCheckRequest request = requestWithCount(1);

        RuleResult result = evaluator.evaluate(policy, request);

        assertThat(result.passed()).isFalse();
        assertThat(result.reason()).isEqualTo("internal evaluator error");
    }

    private RiskPolicy policyWithMax(String max) {
        RiskPolicy policy = new RiskPolicy();
        policy.setRuleType(RiskRuleType.ORDER_FREQUENCY);
        policy.setParams(Map.of("maxPerMinute", max));
        return policy;
    }

    private RiskCheckRequest requestWithCount(int recentOrderCount) {
        return new RiskCheckRequest(
                1L,
                1L,
                1L,
                "BTC/USDT",
                OrderSide.SELL,
                OrderType.MARKET,
                new BigDecimal("1.0"),
                null,
                new BigDecimal("42000"),
                recentOrderCount,
                BigDecimal.ZERO,
                MarketType.SPOT,
                null,
                null, null,
                "req-1");
    }
}
