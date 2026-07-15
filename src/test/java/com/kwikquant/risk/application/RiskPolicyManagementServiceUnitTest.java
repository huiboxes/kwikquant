package com.kwikquant.risk.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.kwikquant.account.application.ExchangeAccountService;
import com.kwikquant.risk.domain.RiskPolicy;
import com.kwikquant.risk.domain.RiskRuleType;
import com.kwikquant.risk.infrastructure.RiskPolicyMapper;
import com.kwikquant.shared.infra.ResourceStateConflictException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Pure-Mockito unit tests for {@link RiskPolicyManagementService#validateParams}.
 *
 * <p>The integration test ({@link RiskPolicyManagementServiceTest}) exercises validateParams
 * indirectly via {@code create()}. The {@code NumberFormatException} / missing / non-positive
 * branches for the rule types are only reachable by calling the package-private
 * {@code validateParams} directly.
 *
 * <p>Also covers {@code warnUnknownKeys}'s warn branch (extra keys with a valid required key).
 */
class RiskPolicyManagementServiceUnitTest {

    private final RiskPolicyMapper policyMapper = mock(RiskPolicyMapper.class);
    private final ExchangeAccountService exchangeAccountService = mock(ExchangeAccountService.class);
    private final RiskPolicyManagementService service =
            new RiskPolicyManagementService(policyMapper, exchangeAccountService, List.of());

    private RiskPolicy seedPolicy() {
        RiskPolicy policy = new RiskPolicy();
        policy.setId(1L);
        policy.setAccountId(10L);
        policy.setRuleType(RiskRuleType.MAX_NOTIONAL);
        policy.setName("orig");
        policy.setParams(Map.of("maxNotionalUsdt", "50000"));
        policy.setEnabled(true);
        return policy;
    }

    // --- deep-defense (Round 2 + Round 3)：mapper 深防返回 0 时 Service 必须抛 4009 ---

    @Test
    void update_deepDefenseFails_throwsConflict() {
        RiskPolicy policy = seedPolicy();
        when(policyMapper.findById(1L)).thenReturn(policy);
        when(policyMapper.updateNameAndParamsWithOwner(any(RiskPolicy.class), anyLong()))
                .thenReturn(0);

        assertThatThrownBy(() -> service.update(1L, 42L, "new-name", Map.of("maxNotionalUsdt", "99999")))
                .isInstanceOf(ResourceStateConflictException.class)
                .hasMessageContaining("risk_policy");
    }

    @Test
    void toggle_deepDefenseFails_throwsConflict() {
        RiskPolicy policy = seedPolicy();
        when(policyMapper.findById(1L)).thenReturn(policy);
        when(policyMapper.updateEnabledWithOwner(eq(1L), anyBoolean(), anyLong()))
                .thenReturn(0);

        assertThatThrownBy(() -> service.toggle(1L, 42L, false))
                .isInstanceOf(ResourceStateConflictException.class)
                .hasMessageContaining("risk_policy")
                .hasMessageContaining("1");
    }

    @Test
    void delete_deepDefenseFails_throwsConflict() {
        RiskPolicy policy = seedPolicy();
        when(policyMapper.findById(1L)).thenReturn(policy);
        when(policyMapper.deleteByIdWithOwner(1L, 42L)).thenReturn(0);

        assertThatThrownBy(() -> service.delete(1L, 42L))
                .isInstanceOf(ResourceStateConflictException.class)
                .hasMessageContaining("risk_policy")
                .hasMessageContaining("1");
    }

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

    // --- DAILY_LOSS_LIMIT ---

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
