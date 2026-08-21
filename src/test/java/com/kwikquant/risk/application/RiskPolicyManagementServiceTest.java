package com.kwikquant.risk.application;

import static org.assertj.core.api.Assertions.*;

import com.kwikquant.AbstractIntegrationTest;
import com.kwikquant.KwikquantApplication;
import com.kwikquant.account.domain.ExchangeAccount;
import com.kwikquant.account.domain.User;
import com.kwikquant.account.infrastructure.ExchangeAccountMapper;
import com.kwikquant.account.infrastructure.UserMapper;
import com.kwikquant.risk.domain.RiskPolicy;
import com.kwikquant.risk.domain.RiskPolicyConflictException;
import com.kwikquant.risk.domain.RiskPolicyNotFoundException;
import com.kwikquant.risk.domain.RiskRuleType;
import com.kwikquant.risk.infrastructure.RiskPolicyMapper;
import com.kwikquant.shared.infra.OwnershipViolationException;
import com.kwikquant.shared.types.Exchange;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest(classes = KwikquantApplication.class)
@TestPropertySource(
        properties = {
            "JWT_SECRET=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
            "ENCRYPTION_KEY=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
        })
class RiskPolicyManagementServiceTest extends AbstractIntegrationTest {

    @Autowired
    RiskPolicyManagementService managementService;

    @Autowired
    RiskPolicyMapper policyMapper;

    @Autowired
    UserMapper userMapper;

    @Autowired
    ExchangeAccountMapper exchangeAccountMapper;

    private long testUserId;
    private long testAccountId;

    @BeforeEach
    void setUp() {
        // Create a test user
        User user = new User(
                "riskuser-" + System.nanoTime(), "riskuser-" + System.nanoTime() + "@test.com", "$2a$10$dummyhash");
        userMapper.insert(user);
        testUserId = user.getId();

        // Create a test exchange account owned by this user
        ExchangeAccount account = new ExchangeAccount();
        account.setUserId(testUserId);
        account.setExchange(Exchange.BINANCE);
        account.setLabel("risk-test");
        account.setApiKeyCiphertext(new byte[32]);
        account.setApiKeyNonce(new byte[12]);
        account.setApiKeyKeyVersion(1);
        account.setApiSecretCiphertext(new byte[32]);
        account.setApiSecretNonce(new byte[12]);
        account.setApiSecretKeyVersion(1);
        account.setPaperTrading(true);
        account.setStatus("ACTIVE");
        exchangeAccountMapper.insert(account);
        testAccountId = account.getId();
    }

    @Test
    void create_persistsPolicy() {
        RiskPolicy policy = managementService.create(
                testAccountId,
                testUserId,
                RiskRuleType.MAX_NOTIONAL,
                "My Max Notional",
                Map.of("maxNotionalUsdt", "50000"));

        assertThat(policy.getId()).isNotNull();
        assertThat(policy.getAccountId()).isEqualTo(testAccountId);
        assertThat(policy.getRuleType()).isEqualTo(RiskRuleType.MAX_NOTIONAL);
        assertThat(policy.isEnabled()).isTrue();

        // Verify persistence
        RiskPolicy loaded = policyMapper.findById(policy.getId());
        assertThat(loaded).isNotNull();
        assertThat(loaded.getName()).isEqualTo("My Max Notional");
        assertThat(loaded.getParams()).containsEntry("maxNotionalUsdt", "50000");
    }

    @Test
    void create_whenDuplicateRuleType_throwsRiskPolicyConflict() {
        managementService.create(
                testAccountId, testUserId, RiskRuleType.MAX_NOTIONAL, "First", Map.of("maxNotionalUsdt", "50000"));

        // Same account + same ruleType → uk_risk_policies_acct_type UK violation → 409
        assertThatThrownBy(() -> managementService.create(
                        testAccountId,
                        testUserId,
                        RiskRuleType.MAX_NOTIONAL,
                        "Second",
                        Map.of("maxNotionalUsdt", "100000")))
                .isInstanceOf(RiskPolicyConflictException.class);
    }

    @Test
    void create_invalidParams_negativeMaxNotional_throws() {
        assertThatThrownBy(() -> managementService.create(
                        testAccountId,
                        testUserId,
                        RiskRuleType.MAX_NOTIONAL,
                        "Bad Policy",
                        Map.of("maxNotionalUsdt", "-100")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxNotionalUsdt must be > 0");
    }

    @Test
    void create_oversizedParamsMap_throws() {
        Map<String, String> bigParams = new HashMap<>();
        bigParams.put("maxNotionalUsdt", "50000");
        for (int i = 0; i < 11; i++) {
            bigParams.put("extra" + i, "value" + i);
        }

        assertThatThrownBy(() -> managementService.create(
                        testAccountId, testUserId, RiskRuleType.MAX_NOTIONAL, "Big Policy", bigParams))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds maximum");
    }

    @Test
    void create_missingRequiredParam_throws() {
        assertThatThrownBy(() -> managementService.create(
                        testAccountId, testUserId, RiskRuleType.MAX_NOTIONAL, "Missing Param", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxNotionalUsdt is required");
    }

    @Test
    void update_modifiesPolicy() {
        RiskPolicy policy = managementService.create(
                testAccountId, testUserId, RiskRuleType.MAX_NOTIONAL, "Original", Map.of("maxNotionalUsdt", "10000"));

        RiskPolicy updated =
                managementService.update(policy.getId(), testUserId, "Updated", Map.of("maxNotionalUsdt", "20000"));

        assertThat(updated.getName()).isEqualTo("Updated");
        assertThat(updated.getParams()).containsEntry("maxNotionalUsdt", "20000");
    }

    @Test
    void toggle_changesEnabledState() {
        RiskPolicy policy = managementService.create(
                testAccountId, testUserId, RiskRuleType.ORDER_FREQUENCY, "Order Freq", Map.of("maxPerMinute", "60"));

        assertThat(policy.isEnabled()).isTrue();

        RiskPolicy toggled = managementService.toggle(policy.getId(), testUserId, false);
        assertThat(toggled.isEnabled()).isFalse();

        RiskPolicy reloaded = policyMapper.findById(policy.getId());
        assertThat(reloaded.isEnabled()).isFalse();
    }

    @Test
    void delete_removesPolicy() {
        RiskPolicy policy = managementService.create(
                testAccountId, testUserId, RiskRuleType.ORDER_FREQUENCY, "Frequency", Map.of("maxPerMinute", "10"));

        managementService.delete(policy.getId(), testUserId);
        assertThat(policyMapper.findById(policy.getId())).isNull();
    }

    @Test
    void delete_nonexistentPolicy_throws() {
        assertThatThrownBy(() -> managementService.delete(999999L, testUserId))
                .isInstanceOf(RiskPolicyNotFoundException.class);
    }

    @Test
    void listByAccount_returnsAllPolicies() {
        managementService.create(
                testAccountId,
                testUserId,
                RiskRuleType.MAX_NOTIONAL,
                "Max Notional",
                Map.of("maxNotionalUsdt", "50000"));

        managementService.create(
                testAccountId, testUserId, RiskRuleType.ORDER_FREQUENCY, "Order Freq", Map.of("maxPerMinute", "60"));

        List<RiskPolicy> policies = managementService.listByAccount(testAccountId, testUserId);
        assertThat(policies).hasSize(2);
    }

    @Test
    void ownershipCheck_differentUser_throws() {
        long otherUserId = testUserId + 999;

        assertThatThrownBy(() -> managementService.create(
                        testAccountId,
                        otherUserId,
                        RiskRuleType.MAX_NOTIONAL,
                        "Not my account",
                        Map.of("maxNotionalUsdt", "50000")))
                .isInstanceOf(OwnershipViolationException.class);
    }

    @Test
    void create_dailyLossLimit_succeeds() {
        RiskPolicy policy = managementService.create(
                testAccountId, testUserId, RiskRuleType.DAILY_LOSS_LIMIT, "Daily Loss", Map.of("maxLossUsdt", "5000"));

        assertThat(policy.getId()).isNotNull();
        assertThat(policy.getRuleType()).isEqualTo(RiskRuleType.DAILY_LOSS_LIMIT);
        assertThat(policy.getParams()).containsEntry("maxLossUsdt", "5000");
    }

    @Test
    void create_orderFrequency_validParams() {
        RiskPolicy policy = managementService.create(
                testAccountId, testUserId, RiskRuleType.ORDER_FREQUENCY, "Order Freq", Map.of("maxPerMinute", "60"));

        assertThat(policy.getId()).isNotNull();
        assertThat(policy.getRuleType()).isEqualTo(RiskRuleType.ORDER_FREQUENCY);
    }

    @Test
    void create_orderFrequency_exceedsMax_throws() {
        assertThatThrownBy(() -> managementService.create(
                        testAccountId,
                        testUserId,
                        RiskRuleType.ORDER_FREQUENCY,
                        "Too Fast",
                        Map.of("maxPerMinute", "1001")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxPerMinute must be <= 1000");
    }

    @Test
    void create_maxNotional_exceedsMax_throws() {
        assertThatThrownBy(() -> managementService.create(
                        testAccountId,
                        testUserId,
                        RiskRuleType.MAX_NOTIONAL,
                        "Too Big",
                        Map.of("maxNotionalUsdt", "10000001")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxNotionalUsdt must be <= 10000000");
    }

    // ---------- applyBulk(自然语言风控确认落库:事务原子 create-or-update) ----------

    @Test
    void applyBulk_createAndUpdateMix_persistsBoth() {
        RiskPolicy existing = managementService.create(
                testAccountId, testUserId, RiskRuleType.MAX_NOTIONAL, "旧规则", Map.of("maxNotionalUsdt", "1000"));

        List<RiskPolicy> applied = managementService.applyBulk(
                testAccountId,
                testUserId,
                List.of(
                        new RiskPolicyApplyItem(
                                existing.getId(),
                                RiskRuleType.MAX_NOTIONAL,
                                "覆盖后的单笔上限",
                                Map.of("maxNotionalUsdt", "2000")),
                        new RiskPolicyApplyItem(
                                null, RiskRuleType.ORDER_FREQUENCY, "频率上限", Map.of("maxPerMinute", "5"))));

        assertThat(applied).hasSize(2);
        // 更新项:id 不变,name/params 覆盖
        RiskPolicy reloadedUpdated = policyMapper.findById(existing.getId());
        assertThat(reloadedUpdated.getName()).isEqualTo("覆盖后的单笔上限");
        assertThat(reloadedUpdated.getParams()).containsEntry("maxNotionalUsdt", "2000");
        // 新建项:落库可查
        assertThat(managementService.listByAccount(testAccountId, testUserId)).hasSize(2);
    }

    @Test
    void applyBulk_secondItemInvalid_firstItemRolledBack() {
        // 原子性:第二条参数非法 → 整体回滚,第一条不落库(风控配置不允许"半生效")
        assertThatThrownBy(() -> managementService.applyBulk(
                        testAccountId,
                        testUserId,
                        List.of(
                                new RiskPolicyApplyItem(
                                        null, RiskRuleType.MAX_NOTIONAL, "合法条", Map.of("maxNotionalUsdt", "5000")),
                                new RiskPolicyApplyItem(
                                        null, RiskRuleType.ORDER_FREQUENCY, "非法条", Map.of("maxPerMinute", "99999")))))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(managementService.listByAccount(testAccountId, testUserId)).isEmpty();
    }

    @Test
    void applyBulk_conflictWithExistingPolicy_throwsConflict() {
        managementService.create(
                testAccountId, testUserId, RiskRuleType.MAX_NOTIONAL, "已存在", Map.of("maxNotionalUsdt", "1000"));

        // 不带 policyId 新建同 ruleType → UK 冲突(2011),与 create 语义一致
        assertThatThrownBy(() -> managementService.applyBulk(
                        testAccountId,
                        testUserId,
                        List.of(new RiskPolicyApplyItem(
                                null, RiskRuleType.MAX_NOTIONAL, "重复新建", Map.of("maxNotionalUsdt", "2000")))))
                .isInstanceOf(RiskPolicyConflictException.class);
    }

    @Test
    void applyBulk_otherUsersAccount_throwsOwnershipViolation() {
        assertThatThrownBy(() -> managementService.applyBulk(
                        testAccountId,
                        testUserId + 999,
                        List.of(new RiskPolicyApplyItem(
                                null, RiskRuleType.MAX_NOTIONAL, "越权", Map.of("maxNotionalUsdt", "5000")))))
                .isInstanceOf(OwnershipViolationException.class);
        assertThat(managementService.listByAccount(testAccountId, testUserId)).isEmpty();
    }
}
