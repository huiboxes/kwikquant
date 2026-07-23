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

class DailyLossLimitEvaluatorTest {

    private final DailyLossLimitEvaluator evaluator = new DailyLossLimitEvaluator();

    @Test
    void supportedType() {
        assertThat(evaluator.supportedType()).isEqualTo(RiskRuleType.DAILY_LOSS_LIMIT);
    }

    @Test
    void withinLimit_passes() {
        RiskPolicy policy = new RiskPolicy();
        policy.setRuleType(RiskRuleType.DAILY_LOSS_LIMIT);
        policy.setParams(Map.of("maxLossUsdt", "5000"));

        RiskCheckRequest request = new RiskCheckRequest(
                1L,
                1L,
                1L,
                "BTC/USDT",
                OrderSide.BUY,
                OrderType.LIMIT,
                new BigDecimal("0.1"),
                new BigDecimal("42000"),
                new BigDecimal("4200"),
                0,
                new BigDecimal("-3000"),
                MarketType.SPOT,
                null,
                null, null,
                "req-1");

        RuleResult result = evaluator.evaluate(policy, request);

        assertThat(result.passed()).isTrue();
        assertThat(result.reason()).isNull();
    }

    @Test
    void exceedsLimit_rejects() {
        RiskPolicy policy = new RiskPolicy();
        policy.setRuleType(RiskRuleType.DAILY_LOSS_LIMIT);
        policy.setParams(Map.of("maxLossUsdt", "5000"));

        RiskCheckRequest request = new RiskCheckRequest(
                1L,
                1L,
                1L,
                "BTC/USDT",
                OrderSide.BUY,
                OrderType.LIMIT,
                new BigDecimal("0.1"),
                new BigDecimal("42000"),
                new BigDecimal("4200"),
                0,
                new BigDecimal("-6000"),
                MarketType.SPOT,
                null,
                null, null,
                "req-1");

        RuleResult result = evaluator.evaluate(policy, request);

        assertThat(result.passed()).isFalse();
        assertThat(result.reason()).contains("daily loss limit exceeded");
    }

    @Test
    void missingMaxLossParam_rejects() {
        RiskPolicy policy = new RiskPolicy();
        policy.setRuleType(RiskRuleType.DAILY_LOSS_LIMIT);
        policy.setParams(Map.of());

        RiskCheckRequest request = new RiskCheckRequest(
                1L,
                1L,
                1L,
                "BTC/USDT",
                OrderSide.BUY,
                OrderType.LIMIT,
                new BigDecimal("0.1"),
                new BigDecimal("42000"),
                null,
                0,
                BigDecimal.ZERO,
                MarketType.SPOT,
                null,
                null, null,
                "req-1");

        RuleResult result = evaluator.evaluate(policy, request);

        assertThat(result.passed()).isFalse();
        assertThat(result.reason()).contains("maxLossUsdt not configured");
    }

    @Test
    void nullDailyPnl_treatedAsZero_passes() {
        RiskPolicy policy = new RiskPolicy();
        policy.setRuleType(RiskRuleType.DAILY_LOSS_LIMIT);
        policy.setParams(Map.of("maxLossUsdt", "5000"));

        RiskCheckRequest request = new RiskCheckRequest(
                1L,
                1L,
                1L,
                "BTC/USDT",
                OrderSide.BUY,
                OrderType.LIMIT,
                new BigDecimal("0.1"),
                new BigDecimal("42000"),
                null,
                0,
                null,
                MarketType.SPOT,
                null,
                null, null,
                "req-1");

        RuleResult result = evaluator.evaluate(policy, request);

        assertThat(result.passed()).isTrue();
    }
}
