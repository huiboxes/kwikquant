package com.kwikquant.risk.infrastructure;

import static org.assertj.core.api.Assertions.*;

import com.kwikquant.AbstractIntegrationTest;
import com.kwikquant.KwikquantApplication;
import com.kwikquant.risk.domain.RiskPolicy;
import com.kwikquant.risk.domain.RiskRuleType;
import java.util.List;
import java.util.Map;
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
class RiskPolicyMapperTest extends AbstractIntegrationTest {

    @Autowired
    RiskPolicyMapper policyMapper;

    @Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbc;

    private static long uniqueAccountId() {
        return System.nanoTime() % 10_000_000L;
    }

    /** Wave 10: findByUserId 通过 EXISTS 关联 exchange_accounts 校验 owner, 需先 seed user + account。 */
    @Test
    void findByUserId_returnsAllPoliciesAcrossAccounts() {
        long userId = seedUser();
        long acct1 = seedExchangeAccount(userId);
        long acct2 = seedExchangeAccount(userId);

        RiskPolicy p1 = new RiskPolicy();
        p1.setAccountId(acct1);
        p1.setRuleType(RiskRuleType.MAX_NOTIONAL);
        p1.setName("p1");
        p1.setParams(Map.of("maxNotionalUsdt", "10000"));
        p1.setEnabled(true);
        policyMapper.insert(p1);

        RiskPolicy p2 = new RiskPolicy();
        p2.setAccountId(acct2);
        p2.setRuleType(RiskRuleType.DAILY_LOSS_LIMIT);
        p2.setName("p2");
        p2.setParams(Map.of("dailyLossLimit", "500"));
        p2.setEnabled(false);
        policyMapper.insert(p2);

        List<RiskPolicy> all = policyMapper.findByUserId(userId);
        assertThat(all).hasSize(2);
        assertThat(all).extracting(RiskPolicy::getAccountId).contains(acct1, acct2);
    }

    @Test
    void findByUserId_whenUserHasNoAccounts_returnsEmpty() {
        long userId = seedUser();
        List<RiskPolicy> all = policyMapper.findByUserId(userId);
        assertThat(all).isEmpty();
    }

    /** R4-01: EXISTS 子查询的全部安全意义——account 属另一 user 的 policy 必须不被返回。 */
    @Test
    void findByUserId_excludesPoliciesOfOtherUser() {
        long userA = seedUser();
        long userB = seedUser();
        long acctA = seedExchangeAccount(userA);
        long acctB = seedExchangeAccount(userB);

        RiskPolicy pA = new RiskPolicy();
        pA.setAccountId(acctA);
        pA.setRuleType(RiskRuleType.MAX_NOTIONAL);
        pA.setName("pA");
        pA.setParams(Map.of("maxNotionalUsdt", "10000"));
        pA.setEnabled(true);
        policyMapper.insert(pA);

        RiskPolicy pB = new RiskPolicy();
        pB.setAccountId(acctB);
        pB.setRuleType(RiskRuleType.DAILY_LOSS_LIMIT);
        pB.setName("pB");
        pB.setParams(Map.of("dailyLossLimit", "500"));
        pB.setEnabled(true);
        policyMapper.insert(pB);

        List<RiskPolicy> forA = policyMapper.findByUserId(userA);
        assertThat(forA).hasSize(1);
        assertThat(forA.get(0).getAccountId()).isEqualTo(acctA);
        assertThat(forA).extracting(RiskPolicy::getAccountId).doesNotContain(acctB);

        List<RiskPolicy> forB = policyMapper.findByUserId(userB);
        assertThat(forB).hasSize(1);
        assertThat(forB.get(0).getAccountId()).isEqualTo(acctB);
    }

    private long seedUser() {
        long nano = System.nanoTime();
        return jdbc.queryForObject(
                "INSERT INTO users (username, email, password_hash) VALUES (?, ?, ?) RETURNING id",
                Long.class,
                "u" + nano,
                "u" + nano + "@test",
                "hash");
    }

    private long seedExchangeAccount(long userId) {
        return jdbc.queryForObject(
                "INSERT INTO exchange_accounts (user_id, exchange, label, api_key, api_secret, nonce, key_version, paper_trading) "
                        + "VALUES (?, 'BINANCE', 'test', ?, ?, ?, 1, true) RETURNING id",
                Long.class,
                userId,
                "key" + System.nanoTime(),
                new byte[] {1},
                new byte[] {1});
    }

    @Test
    void insertAndFindById() {
        long acct = uniqueAccountId();
        RiskPolicy policy = new RiskPolicy();
        policy.setAccountId(acct);
        policy.setRuleType(RiskRuleType.MAX_NOTIONAL);
        policy.setName("Max Notional Check");
        policy.setParams(Map.of("maxNotionalUsdt", "50000.00"));
        policy.setEnabled(true);
        policyMapper.insert(policy);

        assertThat(policy.getId()).isNotNull();

        RiskPolicy loaded = policyMapper.findById(policy.getId());
        assertThat(loaded).isNotNull();
        assertThat(loaded.getAccountId()).isEqualTo(acct);
        assertThat(loaded.getRuleType()).isEqualTo(RiskRuleType.MAX_NOTIONAL);
        assertThat(loaded.getName()).isEqualTo("Max Notional Check");
        assertThat(loaded.getParams()).containsEntry("maxNotionalUsdt", "50000.00");
        assertThat(loaded.isEnabled()).isTrue();
        assertThat(loaded.getCreatedAt()).isNotNull();
        assertThat(loaded.getUpdatedAt()).isNotNull();
    }

    @Test
    void findEnabledByAccountId_filtersDisabled() {
        long acct = uniqueAccountId();

        RiskPolicy enabled = new RiskPolicy();
        enabled.setAccountId(acct);
        enabled.setRuleType(RiskRuleType.MAX_NOTIONAL);
        enabled.setName("Enabled policy");
        enabled.setParams(Map.of("maxNotionalUsdt", "10000"));
        enabled.setEnabled(true);
        policyMapper.insert(enabled);

        RiskPolicy disabled = new RiskPolicy();
        disabled.setAccountId(acct);
        disabled.setRuleType(RiskRuleType.DAILY_LOSS_LIMIT);
        disabled.setName("Disabled policy");
        disabled.setParams(Map.of("maxLossUsdt", "5000"));
        disabled.setEnabled(false);
        policyMapper.insert(disabled);

        List<RiskPolicy> result = policyMapper.findEnabledByAccountId(acct);
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().getRuleType()).isEqualTo(RiskRuleType.MAX_NOTIONAL);
    }

    @Test
    void findByAccountId_returnsAll() {
        long acct = uniqueAccountId();

        RiskPolicy p1 = new RiskPolicy();
        p1.setAccountId(acct);
        p1.setRuleType(RiskRuleType.MAX_NOTIONAL);
        p1.setName("Policy 1");
        p1.setParams(Map.of("maxNotionalUsdt", "10000"));
        p1.setEnabled(true);
        policyMapper.insert(p1);

        RiskPolicy p2 = new RiskPolicy();
        p2.setAccountId(acct);
        p2.setRuleType(RiskRuleType.ORDER_FREQUENCY);
        p2.setName("Policy 2");
        p2.setParams(Map.of("maxPerMinute", "10"));
        p2.setEnabled(false);
        policyMapper.insert(p2);

        List<RiskPolicy> all = policyMapper.findByAccountId(acct);
        assertThat(all).hasSize(2);
    }

    @Test
    void update_modifiesNameAndParams() {
        long acct = uniqueAccountId();
        RiskPolicy policy = new RiskPolicy();
        policy.setAccountId(acct);
        policy.setRuleType(RiskRuleType.MAX_NOTIONAL);
        policy.setName("Original");
        policy.setParams(Map.of("maxNotionalUsdt", "10000"));
        policy.setEnabled(true);
        policyMapper.insert(policy);

        policy.setName("Updated");
        policy.setParams(Map.of("maxNotionalUsdt", "99999"));
        policy.setEnabled(false);
        int affected = policyMapper.update(policy);
        assertThat(affected).isEqualTo(1);

        RiskPolicy reloaded = policyMapper.findById(policy.getId());
        assertThat(reloaded.getName()).isEqualTo("Updated");
        assertThat(reloaded.getParams()).containsEntry("maxNotionalUsdt", "99999");
        assertThat(reloaded.isEnabled()).isFalse();
    }

    @Test
    void deleteById_removesPolicy() {
        long acct = uniqueAccountId();
        RiskPolicy policy = new RiskPolicy();
        policy.setAccountId(acct);
        policy.setRuleType(RiskRuleType.MAX_NOTIONAL);
        policy.setName("To delete");
        policy.setParams(Map.of("maxNotionalUsdt", "10000"));
        policy.setEnabled(true);
        policyMapper.insert(policy);

        int affected = policyMapper.deleteById(policy.getId());
        assertThat(affected).isEqualTo(1);
        assertThat(policyMapper.findById(policy.getId())).isNull();
    }

    @Test
    void jsonbParams_multipleKeys() {
        long acct = uniqueAccountId();
        RiskPolicy policy = new RiskPolicy();
        policy.setAccountId(acct);
        policy.setRuleType(RiskRuleType.MAX_NOTIONAL);
        policy.setName("Multi-param");
        policy.setParams(Map.of("maxNotionalUsdt", "50000", "extra", "value"));
        policy.setEnabled(true);
        policyMapper.insert(policy);

        RiskPolicy loaded = policyMapper.findById(policy.getId());
        assertThat(loaded.getParams()).hasSize(2);
        assertThat(loaded.getParams()).containsEntry("maxNotionalUsdt", "50000");
        assertThat(loaded.getParams()).containsEntry("extra", "value");
    }
}
