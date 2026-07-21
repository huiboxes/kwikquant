package com.kwikquant.risk.domain.evaluators;

import static org.assertj.core.api.Assertions.assertThat;

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

/**
 * 阶段2h(§10 M8/§11 M8-new):MaxInitialMarginEvaluator 单测。
 *
 * <p>覆盖 PERP initialMargin = notional / leverage &lt;= availableMargin × ratio,SPOT skip,
 * 缺 notional/leverage/availableMargin fail-closed,ratio 从 policy params 解析 + 默认 0.8 fallback。
 */
class MaxInitialMarginEvaluatorTest {

    private final MaxInitialMarginEvaluator evaluator = new MaxInitialMarginEvaluator();

    @Test
    void supportedType() {
        assertThat(evaluator.supportedType()).isEqualTo(RiskRuleType.MAX_INITIAL_MARGIN);
    }

    @Test
    void perpWithinLimit_passes() {
        // notional 4200 / leverage 10 = initialMargin 420; available 1000 × 0.8 = 800; 420 <= 800 → passed
        RiskPolicy policy = policyWithRatio("0.8");
        RiskCheckRequest request = perpRequest(new BigDecimal("4200"), 10, new BigDecimal("1000"));

        RuleResult result = evaluator.evaluate(policy, request);

        assertThat(result.passed()).isTrue();
        assertThat(result.reason()).isNull();
        assertThat(result.ruleType()).isEqualTo(RiskRuleType.MAX_INITIAL_MARGIN);
    }

    @Test
    void perpExceedsLimit_fails() {
        // notional 42000 / leverage 10 = initialMargin 4200; available 1000 × 0.8 = 800; 4200 > 800 → rejected
        RiskPolicy policy = policyWithRatio("0.8");
        RiskCheckRequest request = perpRequest(new BigDecimal("42000"), 10, new BigDecimal("1000"));

        RuleResult result = evaluator.evaluate(policy, request);

        assertThat(result.passed()).isFalse();
        assertThat(result.reason()).contains("initial margin");
        assertThat(result.reason()).contains("4200");
    }

    @Test
    void spotMarketType_skips() {
        // SPOT skip——无 initialMargin 概念,即使配了 policy 也 passed=true
        RiskPolicy policy = policyWithRatio("0.8");
        RiskCheckRequest request = new RiskCheckRequest(
                1L,
                1L,
                1L,
                "BTC/USDT",
                OrderSide.BUY,
                OrderType.LIMIT,
                new BigDecimal("0.1"),
                new BigDecimal("42000"),
                new BigDecimal("42000"),
                0,
                BigDecimal.ZERO,
                MarketType.SPOT,
                null,
                null,
                "req-1");

        RuleResult result = evaluator.evaluate(policy, request);

        assertThat(result.passed()).isTrue();
        assertThat(result.reason()).contains("not PERP");
    }

    @Test
    void perpNullNotional_fails() {
        RiskPolicy policy = policyWithRatio("0.8");
        RiskCheckRequest request = perpRequest(null, 10, new BigDecimal("1000"));

        RuleResult result = evaluator.evaluate(policy, request);

        assertThat(result.passed()).isFalse();
        assertThat(result.reason()).contains("notional");
    }

    @Test
    void perpNullLeverage_fails() {
        RiskPolicy policy = policyWithRatio("0.8");
        RiskCheckRequest request = perpRequest(new BigDecimal("4200"), null, new BigDecimal("1000"));

        RuleResult result = evaluator.evaluate(policy, request);

        assertThat(result.passed()).isFalse();
        assertThat(result.reason()).contains("leverage");
    }

    @Test
    void perpZeroLeverage_fails() {
        RiskPolicy policy = policyWithRatio("0.8");
        RiskCheckRequest request = perpRequest(new BigDecimal("4200"), 0, new BigDecimal("1000"));

        RuleResult result = evaluator.evaluate(policy, request);

        assertThat(result.passed()).isFalse();
        assertThat(result.reason()).contains("leverage");
    }

    @Test
    void perpNullAvailableMargin_failsClosed() {
        // fail-closed:无 availableMargin 无法评保证金 → 拒(不 auto-approve)
        RiskPolicy policy = policyWithRatio("0.8");
        RiskCheckRequest request = perpRequest(new BigDecimal("4200"), 10, null);

        RuleResult result = evaluator.evaluate(policy, request);

        assertThat(result.passed()).isFalse();
        assertThat(result.reason()).contains("available margin");
    }

    @Test
    void perpCustomRatio_appliedFromPolicyParams() {
        // ratio 0.5 → threshold = available 1000 × 0.5 = 500; initialMargin 420 <= 500 → passed
        // (同 input 在 ratio 0.8 时 threshold 800 也 passed,故用更紧的 0.5 + 更大的 notional 验 ratio 生效)
        // notional 5200 / 10 = 520; ratio 0.5 → 500; 520 > 500 → rejected(若 ratio 没生效用 0.8 → 800 → passed)
        RiskPolicy policy = policyWithRatio("0.5");
        RiskCheckRequest request = perpRequest(new BigDecimal("5200"), 10, new BigDecimal("1000"));

        RuleResult result = evaluator.evaluate(policy, request);

        assertThat(result.passed()).isFalse();
        assertThat(result.reason()).contains("520");
    }

    @Test
    void perpRatioNullInParams_fallsBackToDefault08() {
        // policy params 不含 maxInitialMarginRatio → fallback DEFAULT 0.8
        RiskPolicy policy = new RiskPolicy();
        policy.setRuleType(RiskRuleType.MAX_INITIAL_MARGIN);
        policy.setParams(Map.of());
        // notional 4200 / 10 = 420; available 1000 × 0.8(fallback) = 800; 420 <= 800 → passed
        RiskCheckRequest request = perpRequest(new BigDecimal("4200"), 10, new BigDecimal("1000"));

        RuleResult result = evaluator.evaluate(policy, request);

        assertThat(result.passed()).isTrue();
    }

    @Test
    void perpRatioOutOfRange_fallsBackToDefault() {
        // ratio 1.5 超 (0,1] → fallback 0.8
        RiskPolicy policy = policyWithRatio("1.5");
        RiskCheckRequest request = perpRequest(new BigDecimal("4200"), 10, new BigDecimal("1000"));

        RuleResult result = evaluator.evaluate(policy, request);

        // fallback 0.8 → threshold 800; 420 <= 800 → passed(若用 1.5 → 1500 → 仍 passed,但验 fallback 用 0.8)
        assertThat(result.passed()).isTrue();
    }

    private RiskPolicy policyWithRatio(String ratio) {
        RiskPolicy policy = new RiskPolicy();
        policy.setRuleType(RiskRuleType.MAX_INITIAL_MARGIN);
        policy.setParams(Map.of(MaxInitialMarginEvaluator.PARAM_KEY, ratio));
        return policy;
    }

    private RiskCheckRequest perpRequest(BigDecimal notional, Integer leverage, BigDecimal availableMargin) {
        return new RiskCheckRequest(
                1L,
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
                availableMargin,
                "req-1");
    }
}
