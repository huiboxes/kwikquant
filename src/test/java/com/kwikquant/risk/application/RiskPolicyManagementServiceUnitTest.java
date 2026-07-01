package com.kwikquant.risk.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.kwikquant.account.application.ExchangeAccountService;
import com.kwikquant.risk.domain.RiskRuleType;
import com.kwikquant.risk.infrastructure.RiskPolicyMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Pure-Mockito unit tests for {@link RiskPolicyManagementService#validateParams}.
 *
 * <p>The integration test ({@link RiskPolicyManagementServiceTest}) exercises validateParams
 * indirectly via {@code create()}, but {@code create()} rejects DAILY_LOSS_LIMIT as an
 * unsupported rule type (no registered evaluator in v1) <em>before</em> reaching
 * validateParams. So the entire {@code validateDailyLossLimitParams} branch, plus the
 * {@code NumberFormatException} / missing / non-positive branches for the other rule types,
 * are only reachable by calling the package-private {@code validateParams} directly.
 *
 * <p>Also covers {@code warnUnknownKeys}'s warn branch (extra keys with a valid required key).
 */
class RiskPolicyManagementServiceUnitTest {

    private final RiskPolicyManagementService service = new RiskPolicyManagementService(
            mock(RiskPolicyMapper.class), mock(ExchangeAccountService.class), List.of());

    // --- null / size guard ---

    @Test
    void validateParams_nullParams_throws() {
        assertThatThrownBy(() -> service.validateParams(RiskRuleType.MAX_NOTIONAL, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("params must not be null");
    }

    // --- MAX_NOTIONAL ---

    @Test
    void validateParams_maxNotional_badNumber_throws() {
        assertThatThrownBy(() -> service.validateParams(RiskRuleType.MAX_NOTIONAL, Map.of("maxNotionalUsdt", "abc")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxNotionalUsdt must be a valid decimal");
    }

    @Test
    void validateParams_maxNotional_withUnknownKey_doesNotThrow() {
        // Valid required key + an unknown extra key → warn (logged) but not rejected.
        assertThatCode(() -> service.validateParams(
                        RiskRuleType.MAX_NOTIONAL, Map.of("maxNotionalUsdt", "50000", "extraKey", "ignored")))
                .doesNotThrowAnyException();
    }

    // --- DAILY_LOSS_LIMIT (unreachable via create() because the rule type is unsupported in v1) ---

    @Test
    void validateParams_dailyLossLimit_valid_doesNotThrow() {
        assertThatCode(() -> service.validateParams(RiskRuleType.DAILY_LOSS_LIMIT, Map.of("maxLossUsdt", "5000")))
                .doesNotThrowAnyException();
    }

    @Test
    void validateParams_dailyLossLimit_missingRequired_throws() {
        assertThatThrownBy(() -> service.validateParams(RiskRuleType.DAILY_LOSS_LIMIT, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxLossUsdt is required");
    }

    @Test
    void validateParams_dailyLossLimit_badNumber_throws() {
        assertThatThrownBy(() -> service.validateParams(RiskRuleType.DAILY_LOSS_LIMIT, Map.of("maxLossUsdt", "xyz")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxLossUsdt must be a valid decimal");
    }

    @Test
    void validateParams_dailyLossLimit_negative_throws() {
        assertThatThrownBy(() -> service.validateParams(RiskRuleType.DAILY_LOSS_LIMIT, Map.of("maxLossUsdt", "-1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxLossUsdt must be > 0");
    }

    @Test
    void validateParams_dailyLossLimit_exceedsMax_throws() {
        assertThatThrownBy(
                        () -> service.validateParams(RiskRuleType.DAILY_LOSS_LIMIT, Map.of("maxLossUsdt", "10000001")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxLossUsdt must be <= 10000000");
    }

    // --- ORDER_FREQUENCY ---

    @Test
    void validateParams_orderFrequency_missingRequired_throws() {
        assertThatThrownBy(() -> service.validateParams(RiskRuleType.ORDER_FREQUENCY, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxPerMinute is required");
    }

    @Test
    void validateParams_orderFrequency_badNumber_throws() {
        assertThatThrownBy(() -> service.validateParams(RiskRuleType.ORDER_FREQUENCY, Map.of("maxPerMinute", "abc")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxPerMinute must be a valid integer");
    }

    @Test
    void validateParams_orderFrequency_zero_throws() {
        assertThatThrownBy(() -> service.validateParams(RiskRuleType.ORDER_FREQUENCY, Map.of("maxPerMinute", "0")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxPerMinute must be > 0");
    }
}
