package com.kwikquant.risk.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.kwikquant.account.application.ExchangeAccountService;
import com.kwikquant.risk.domain.RiskPolicy;
import com.kwikquant.risk.domain.RiskPolicyNotFoundException;
import com.kwikquant.risk.domain.RiskRuleType;
import com.kwikquant.risk.domain.RuleEvaluator;
import com.kwikquant.risk.infrastructure.RiskPolicyMapper;
import com.kwikquant.shared.infra.ResourceStateConflictException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pure-Mockito unit tests for {@link RiskPolicyManagementService} — deep-defense 0 行冲突与
 * {@code applyBulk} create-or-update 编排。参数校验分支在 {@link RiskPolicyParamValidatorTest}
 * (校验逻辑已抽到 {@link RiskPolicyParamValidator} 共享)。
 */
class RiskPolicyManagementServiceUnitTest {

    private final RiskPolicyMapper policyMapper = mock(RiskPolicyMapper.class);
    private final ExchangeAccountService exchangeAccountService = mock(ExchangeAccountService.class);
    private final RuleEvaluator maxNotionalEvaluator = mock(RuleEvaluator.class);
    private RiskPolicyManagementService service;

    @BeforeEach
    void setUp() {
        when(maxNotionalEvaluator.supportedType()).thenReturn(RiskRuleType.MAX_NOTIONAL);
        service = new RiskPolicyManagementService(policyMapper, exchangeAccountService, List.of(maxNotionalEvaluator));
    }

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

    // --- deep-defense：mapper 深防返回 0 时 Service 必须抛 4009 ---

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

    // --- applyBulk：create-or-update 编排(自然语言风控确认落库) ---

    @Test
    void applyBulk_createAndUpdateMix_delegatesToCreateAndUpdate() {
        // 更新分支:policyId=1 → findById 命中(accountId=10 与目标一致)→ updateNameAndParamsWithOwner
        RiskPolicy existing = seedPolicy();
        when(policyMapper.findById(1L)).thenReturn(existing);
        when(policyMapper.updateNameAndParamsWithOwner(any(RiskPolicy.class), eq(42L)))
                .thenReturn(1);
        // 新建分支:insert 回填生成 id
        doAnswer(inv -> {
                    inv.getArgument(0, RiskPolicy.class).setId(77L);
                    return null;
                })
                .when(policyMapper)
                .insert(any(RiskPolicy.class));

        List<RiskPolicy> applied = service.applyBulk(
                10L,
                42L,
                List.of(
                        new RiskPolicyApplyItem(
                                1L, RiskRuleType.MAX_NOTIONAL, "覆盖更新", Map.of("maxNotionalUsdt", "9999")),
                        new RiskPolicyApplyItem(
                                null, RiskRuleType.MAX_NOTIONAL, "新建", Map.of("maxNotionalUsdt", "5000"))));

        assertThat(applied).hasSize(2);
        assertThat(applied.get(0).getId()).isEqualTo(1L);
        assertThat(applied.get(1).getId()).isEqualTo(77L);
        // 归属校验 3 次:applyBulk 入口一次 + 内层 update/create 各自深度防御复查;insert/update 各一次
        verify(exchangeAccountService, times(3)).getOwned(10L, 42L);
        verify(policyMapper).insert(any(RiskPolicy.class));
        verify(policyMapper).updateNameAndParamsWithOwner(any(RiskPolicy.class), eq(42L));
    }

    @Test
    void applyBulk_policyBelongsToOtherAccount_throwsAndDoesNotWrite() {
        RiskPolicy existing = seedPolicy(); // accountId=10
        when(policyMapper.findById(1L)).thenReturn(existing);

        // 目标账户 99 ≠ policy.accountId 10 → 拒绝(防跨账户错配),不落任何写
        assertThatThrownBy(() -> service.applyBulk(
                        99L,
                        42L,
                        List.of(new RiskPolicyApplyItem(
                                1L, RiskRuleType.MAX_NOTIONAL, "错配", Map.of("maxNotionalUsdt", "9999")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not belong to account");
        verify(policyMapper, never()).updateNameAndParamsWithOwner(any(), anyLong());
        verify(policyMapper, never()).insert(any(RiskPolicy.class));
    }

    @Test
    void applyBulk_unknownPolicyId_throwsNotFound() {
        when(policyMapper.findById(999L)).thenReturn(null);

        assertThatThrownBy(() -> service.applyBulk(
                        10L,
                        42L,
                        List.of(new RiskPolicyApplyItem(
                                999L, RiskRuleType.MAX_NOTIONAL, "不存在", Map.of("maxNotionalUsdt", "9999")))))
                .isInstanceOf(RiskPolicyNotFoundException.class);
    }
}
