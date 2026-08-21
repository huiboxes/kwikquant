package com.kwikquant.risk.application;

import static org.assertj.core.api.Assertions.*;

import com.kwikquant.risk.domain.RiskRuleType;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RiskPolicyParamValidator} — 风控参数校验唯一真相源。
 * 写路径(create/update/applyBulk)与 AI 自然语言解析预览共用,分支需全覆盖。
 */
class RiskPolicyParamValidatorTest {

    // --- null / size guard ---

    @Test
    void validate_nullParams_throws() {
        assertThatThrownBy(() -> RiskPolicyParamValidator.validate(RiskRuleType.MAX_NOTIONAL, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("params must not be null");
    }

    @Test
    void validate_oversizedParamsMap_throws() {
        Map<String, String> bigParams = new HashMap<>();
        bigParams.put("maxNotionalUsdt", "50000");
        for (int i = 0; i < 11; i++) {
            bigParams.put("extra" + i, "value" + i);
        }
        assertThatThrownBy(() -> RiskPolicyParamValidator.validate(RiskRuleType.MAX_NOTIONAL, bigParams))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds maximum");
    }

    // --- MAX_NOTIONAL ---

    @Test
    void validate_maxNotional_valid_doesNotThrow() {
        assertThatCode(() ->
                        RiskPolicyParamValidator.validate(RiskRuleType.MAX_NOTIONAL, Map.of("maxNotionalUsdt", "5000")))
                .doesNotThrowAnyException();
    }

    @Test
    void validate_maxNotional_missingRequired_throws() {
        assertThatThrownBy(() -> RiskPolicyParamValidator.validate(RiskRuleType.MAX_NOTIONAL, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxNotionalUsdt is required");
    }

    @Test
    void validate_maxNotional_badNumber_throws() {
        assertThatThrownBy(() ->
                        RiskPolicyParamValidator.validate(RiskRuleType.MAX_NOTIONAL, Map.of("maxNotionalUsdt", "abc")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxNotionalUsdt must be a valid decimal");
    }

    @Test
    void validate_maxNotional_zero_throws() {
        assertThatThrownBy(() ->
                        RiskPolicyParamValidator.validate(RiskRuleType.MAX_NOTIONAL, Map.of("maxNotionalUsdt", "0")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxNotionalUsdt must be > 0");
    }

    @Test
    void validate_maxNotional_exceedsMax_throws() {
        assertThatThrownBy(() -> RiskPolicyParamValidator.validate(
                        RiskRuleType.MAX_NOTIONAL, Map.of("maxNotionalUsdt", "10000001")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxNotionalUsdt must be <= 10000000");
    }

    @Test
    void validate_maxNotional_withUnknownKey_doesNotThrow() {
        // Valid required key + an unknown extra key → warn (logged) but not rejected.
        assertThatCode(() -> RiskPolicyParamValidator.validate(
                        RiskRuleType.MAX_NOTIONAL, Map.of("maxNotionalUsdt", "50000", "extraKey", "ignored")))
                .doesNotThrowAnyException();
    }

    // --- DAILY_LOSS_LIMIT ---

    @Test
    void validate_dailyLossLimit_valid_doesNotThrow() {
        assertThatCode(() ->
                        RiskPolicyParamValidator.validate(RiskRuleType.DAILY_LOSS_LIMIT, Map.of("maxLossUsdt", "5000")))
                .doesNotThrowAnyException();
    }

    @Test
    void validate_dailyLossLimit_missingRequired_throws() {
        assertThatThrownBy(() -> RiskPolicyParamValidator.validate(RiskRuleType.DAILY_LOSS_LIMIT, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxLossUsdt is required");
    }

    @Test
    void validate_dailyLossLimit_badNumber_throws() {
        assertThatThrownBy(() ->
                        RiskPolicyParamValidator.validate(RiskRuleType.DAILY_LOSS_LIMIT, Map.of("maxLossUsdt", "xyz")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxLossUsdt must be a valid decimal");
    }

    @Test
    void validate_dailyLossLimit_negative_throws() {
        assertThatThrownBy(() ->
                        RiskPolicyParamValidator.validate(RiskRuleType.DAILY_LOSS_LIMIT, Map.of("maxLossUsdt", "-1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxLossUsdt must be > 0");
    }

    @Test
    void validate_dailyLossLimit_exceedsMax_throws() {
        assertThatThrownBy(() -> RiskPolicyParamValidator.validate(
                        RiskRuleType.DAILY_LOSS_LIMIT, Map.of("maxLossUsdt", "10000001")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxLossUsdt must be <= 10000000");
    }

    // --- ORDER_FREQUENCY ---

    @Test
    void validate_orderFrequency_valid_doesNotThrow() {
        assertThatCode(() ->
                        RiskPolicyParamValidator.validate(RiskRuleType.ORDER_FREQUENCY, Map.of("maxPerMinute", "60")))
                .doesNotThrowAnyException();
    }

    @Test
    void validate_orderFrequency_missingRequired_throws() {
        assertThatThrownBy(() -> RiskPolicyParamValidator.validate(RiskRuleType.ORDER_FREQUENCY, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxPerMinute is required");
    }

    @Test
    void validate_orderFrequency_badNumber_throws() {
        assertThatThrownBy(() ->
                        RiskPolicyParamValidator.validate(RiskRuleType.ORDER_FREQUENCY, Map.of("maxPerMinute", "abc")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxPerMinute must be a valid integer");
    }

    @Test
    void validate_orderFrequency_zero_throws() {
        assertThatThrownBy(() ->
                        RiskPolicyParamValidator.validate(RiskRuleType.ORDER_FREQUENCY, Map.of("maxPerMinute", "0")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxPerMinute must be > 0");
    }

    @Test
    void validate_orderFrequency_exceedsMax_throws() {
        assertThatThrownBy(() ->
                        RiskPolicyParamValidator.validate(RiskRuleType.ORDER_FREQUENCY, Map.of("maxPerMinute", "1001")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxPerMinute must be <= 1000");
    }

    // --- MAX_INITIAL_MARGIN ---

    @Test
    void validate_maxInitialMargin_valid_doesNotThrow() {
        assertThatCode(() -> RiskPolicyParamValidator.validate(
                        RiskRuleType.MAX_INITIAL_MARGIN, Map.of("maxInitialMarginRatio", "0.8")))
                .doesNotThrowAnyException();
    }

    @Test
    void validate_maxInitialMargin_oneInclusive_doesNotThrow() {
        assertThatCode(() -> RiskPolicyParamValidator.validate(
                        RiskRuleType.MAX_INITIAL_MARGIN, Map.of("maxInitialMarginRatio", "1")))
                .doesNotThrowAnyException();
    }

    @Test
    void validate_maxInitialMargin_missingRequired_throws() {
        assertThatThrownBy(() -> RiskPolicyParamValidator.validate(RiskRuleType.MAX_INITIAL_MARGIN, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxInitialMarginRatio is required");
    }

    @Test
    void validate_maxInitialMargin_badNumber_throws() {
        assertThatThrownBy(() -> RiskPolicyParamValidator.validate(
                        RiskRuleType.MAX_INITIAL_MARGIN, Map.of("maxInitialMarginRatio", "abc")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxInitialMarginRatio must be a valid decimal");
    }

    @Test
    void validate_maxInitialMargin_zero_throws() {
        assertThatThrownBy(() -> RiskPolicyParamValidator.validate(
                        RiskRuleType.MAX_INITIAL_MARGIN, Map.of("maxInitialMarginRatio", "0")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be in (0, 1]");
    }

    @Test
    void validate_maxInitialMargin_aboveOne_throws() {
        assertThatThrownBy(() -> RiskPolicyParamValidator.validate(
                        RiskRuleType.MAX_INITIAL_MARGIN, Map.of("maxInitialMarginRatio", "1.2")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be in (0, 1]");
    }
}
