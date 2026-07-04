package com.kwikquant.mcp.interfaces;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kwikquant.risk.application.RiskPolicyManagementService;
import com.kwikquant.risk.domain.RiskPolicy;
import com.kwikquant.risk.domain.RiskRuleType;
import com.kwikquant.shared.infra.AuditEntry;
import com.kwikquant.shared.infra.AuditRepository;
import com.kwikquant.shared.infra.CriticalAuditException;
import com.kwikquant.shared.infra.McpEmergencyConfirmRequiredException;
import com.kwikquant.shared.infra.McpToolParamInvalidException;
import com.kwikquant.shared.types.StrategyStatus;
import com.kwikquant.strategy.application.StrategyCrudService;
import com.kwikquant.strategy.application.StrategyLifecycleService;
import com.kwikquant.strategy.domain.StrategyDefinition;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * {@link RiskTools} 单测。验证：get_risk_rules 双路径、set_risk_rules 新建/更新+ruleType 解析、
 * emergency_stop 的 confirm 校验 + 前置审计 + 停 RUNNING + 审计失败 fail-closed + 部分失败返实际数。
 */
class RiskToolsTest {

    private RiskPolicyManagementService policyService;
    private StrategyCrudService strategyCrudService;
    private StrategyLifecycleService lifecycleService;
    private AuditRepository auditRepository;
    private RiskTools tools;

    @BeforeEach
    void setUp() {
        policyService = mock(RiskPolicyManagementService.class);
        strategyCrudService = mock(StrategyCrudService.class);
        lifecycleService = mock(StrategyLifecycleService.class);
        auditRepository = mock(AuditRepository.class);
        tools = new RiskTools(policyService, strategyCrudService, lifecycleService, auditRepository);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("42", "x"));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ── get_risk_rules ──

    @Test
    void getRiskRules_withAccountId_delegatesListByAccount() {
        when(policyService.listByAccount(1L, 42L))
                .thenReturn(List.of(policy(5L, 1L, RiskRuleType.MAX_NOTIONAL, "max", Map.of(), true)));

        var result = tools.getRiskRules(1L);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).ruleType()).isEqualTo("MAX_NOTIONAL");
        verify(policyService).listByAccount(1L, 42L);
        verify(policyService, never()).listByUser(anyLong());
    }

    @Test
    void getRiskRules_noAccountId_delegatesListByUser() {
        when(policyService.listByUser(42L))
                .thenReturn(List.of(policy(5L, 1L, RiskRuleType.DAILY_LOSS_LIMIT, "loss", Map.of(), false)));

        var result = tools.getRiskRules(null);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).enabled()).isFalse();
        verify(policyService).listByUser(42L);
    }

    // ── set_risk_rules ──

    @Test
    void setRiskRules_create_newPolicy_delegatesAndReturns() {
        when(policyService.create(eq(1L), eq(42L), eq(RiskRuleType.MAX_NOTIONAL), eq("max"), any()))
                .thenReturn(policy(10L, 1L, RiskRuleType.MAX_NOTIONAL, "max", Map.of("maxNotional", "10000"), true));

        var v = tools.setRiskRules(null, 1L, "MAX_NOTIONAL", "max", Map.of("maxNotional", "10000"), null);

        assertThat(v.id()).isEqualTo(10L);
        assertThat(v.ruleType()).isEqualTo("MAX_NOTIONAL");
        verify(policyService).create(eq(1L), eq(42L), eq(RiskRuleType.MAX_NOTIONAL), eq("max"), any());
    }

    @Test
    void setRiskRules_create_caseInsensitiveRuleType() {
        when(policyService.create(eq(1L), eq(42L), eq(RiskRuleType.DAILY_LOSS_LIMIT), any(), any()))
                .thenReturn(policy(11L, 1L, RiskRuleType.DAILY_LOSS_LIMIT, "loss", Map.of(), true));

        var v = tools.setRiskRules(null, 1L, "daily_loss_limit", "loss", Map.of(), null);

        assertThat(v.ruleType()).isEqualTo("DAILY_LOSS_LIMIT");
    }

    @Test
    void setRiskRules_create_invalidRuleType_throws10002() {
        assertThatThrownBy(() -> tools.setRiskRules(null, 1L, "INVALID", "x", Map.of(), null))
                .isInstanceOf(McpToolParamInvalidException.class)
                .hasMessageContaining("ruleType");
    }

    @Test
    void setRiskRules_create_missingAccountId_throws10002() {
        assertThatThrownBy(() -> tools.setRiskRules(null, null, "MAX_NOTIONAL", "x", Map.of(), null))
                .isInstanceOf(McpToolParamInvalidException.class)
                .hasMessageContaining("accountId");
    }

    @Test
    void setRiskRules_create_missingRuleType_throws10002() {
        assertThatThrownBy(() -> tools.setRiskRules(null, 1L, null, "x", Map.of(), null))
                .isInstanceOf(McpToolParamInvalidException.class)
                .hasMessageContaining("ruleType");
    }

    @Test
    void setRiskRules_update_existingPolicy_delegatesUpdateOnly() {
        when(policyService.update(eq(5L), eq(42L), eq("newname"), any()))
                .thenReturn(policy(5L, 1L, RiskRuleType.MAX_NOTIONAL, "newname", Map.of(), true));

        var v = tools.setRiskRules(5L, null, "MAX_NOTIONAL", "newname", Map.of("maxNotional", "20000"), null);

        assertThat(v.id()).isEqualTo(5L);
        verify(policyService).update(eq(5L), eq(42L), eq("newname"), any());
        verify(policyService, never()).toggle(anyLong(), anyLong(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void setRiskRules_update_withEnabledToggle() {
        when(policyService.update(eq(5L), eq(42L), eq("n"), any()))
                .thenReturn(policy(5L, 1L, RiskRuleType.MAX_NOTIONAL, "n", Map.of(), true));
        when(policyService.toggle(5L, 42L, false))
                .thenReturn(policy(5L, 1L, RiskRuleType.MAX_NOTIONAL, "n", Map.of(), false));

        var v = tools.setRiskRules(5L, null, null, "n", null, false);

        assertThat(v.enabled()).isFalse();
        verify(policyService).toggle(5L, 42L, false);
    }

    // ── emergency_stop ──

    @Test
    void emergencyStop_missingConfirm_throws10004() {
        assertThatThrownBy(() -> tools.emergencyStop(null)).isInstanceOf(McpEmergencyConfirmRequiredException.class);
        verify(auditRepository, never()).save(any());
    }

    @Test
    void emergencyStop_confirmFalse_throws10004() {
        assertThatThrownBy(() -> tools.emergencyStop(false)).isInstanceOf(McpEmergencyConfirmRequiredException.class);
        verify(auditRepository, never()).save(any());
    }

    @Test
    void emergencyStop_valid_stopsRunningStrategiesAndReturnsBatchUuid() {
        when(strategyCrudService.listByUser(42L))
                .thenReturn(List.of(
                        strategy(1L, StrategyStatus.RUNNING),
                        strategy(2L, StrategyStatus.STOPPED),
                        strategy(3L, StrategyStatus.RUNNING)));
        when(lifecycleService.stop(anyLong(), eq(42L)))
                .thenAnswer(inv -> strategy(inv.getArgument(0), StrategyStatus.STOPPED));

        var result = tools.emergencyStop(true);

        assertThat(result.batchUuid()).isNotBlank();
        assertThat(result.stoppedCount()).isEqualTo(2);
        assertThat(result.strategyIds()).containsExactly(1L, 3L);
        assertThat(result.failedStrategyIds()).isEmpty();
        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditRepository).save(captor.capture());
        assertThat(captor.getValue().action()).isEqualTo("EMERGENCY_STOP");
        assertThat(captor.getValue().targetType()).isEqualTo("STRATEGY");
        assertThat(captor.getValue().targetId()).isEqualTo(result.batchUuid());
    }

    @Test
    void emergencyStop_noRunningStrategies_returnsZeroCount() {
        when(strategyCrudService.listByUser(42L)).thenReturn(List.of(strategy(1L, StrategyStatus.STOPPED)));

        var result = tools.emergencyStop(true);

        assertThat(result.stoppedCount()).isEqualTo(0);
        assertThat(result.strategyIds()).isEmpty();
        assertThat(result.failedStrategyIds()).isEmpty();
        verify(auditRepository).save(any());
        verify(lifecycleService, never()).stop(anyLong(), anyLong());
    }

    @Test
    void emergencyStop_auditFails_throwsCriticalAuditExceptionAndStrategiesNotStopped() {
        doThrow(new RuntimeException("db down")).when(auditRepository).save(any());

        assertThatThrownBy(() -> tools.emergencyStop(true))
                .isInstanceOf(CriticalAuditException.class)
                .hasMessageContaining("EMERGENCY_STOP");
        verify(strategyCrudService, never()).listByUser(anyLong());
        verify(lifecycleService, never()).stop(anyLong(), anyLong());
    }

    @Test
    void emergencyStop_partialStopFailure_returnsActualCount() {
        when(strategyCrudService.listByUser(42L))
                .thenReturn(List.of(strategy(1L, StrategyStatus.RUNNING), strategy(3L, StrategyStatus.RUNNING)));
        doThrow(new RuntimeException("worker busy")).when(lifecycleService).stop(eq(1L), eq(42L));
        when(lifecycleService.stop(eq(3L), eq(42L))).thenReturn(strategy(3L, StrategyStatus.STOPPED));

        var result = tools.emergencyStop(true);

        assertThat(result.stoppedCount()).isEqualTo(1);
        assertThat(result.strategyIds()).containsExactly(3L);
        // R4-05: 部分失败时 failedStrategyIds 须暴露未停止的策略 ID（kill switch 运维盲区修复）
        assertThat(result.failedStrategyIds()).containsExactly(1L);
    }

    @Test
    void setRiskRules_create_enabledFalse_togglesNewPolicyDisabled() {
        when(policyService.create(eq(1L), eq(42L), eq(RiskRuleType.MAX_NOTIONAL), eq("max"), any()))
                .thenReturn(policy(20L, 1L, RiskRuleType.MAX_NOTIONAL, "max", Map.of(), true));
        when(policyService.toggle(20L, 42L, false))
                .thenReturn(policy(20L, 1L, RiskRuleType.MAX_NOTIONAL, "max", Map.of(), false));

        var v = tools.setRiskRules(null, 1L, "MAX_NOTIONAL", "max", Map.of(), false);

        assertThat(v.id()).isEqualTo(20L);
        assertThat(v.enabled()).isFalse();
        verify(policyService).create(eq(1L), eq(42L), eq(RiskRuleType.MAX_NOTIONAL), eq("max"), any());
        verify(policyService).toggle(20L, 42L, false);
    }

    private static RiskPolicy policy(
            long id, long accountId, RiskRuleType type, String name, Map<String, String> params, boolean enabled) {
        RiskPolicy p = new RiskPolicy();
        p.setId(id);
        p.setAccountId(accountId);
        p.setRuleType(type);
        p.setName(name);
        p.setParams(params);
        p.setEnabled(enabled);
        return p;
    }

    private static StrategyDefinition strategy(long id, StrategyStatus status) {
        StrategyDefinition s = new StrategyDefinition();
        s.setId(id);
        s.setStatus(status);
        return s;
    }
}
