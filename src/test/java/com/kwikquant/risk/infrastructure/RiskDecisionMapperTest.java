package com.kwikquant.risk.infrastructure;

import static org.assertj.core.api.Assertions.*;

import com.kwikquant.AbstractIntegrationTest;
import com.kwikquant.KwikquantApplication;
import com.kwikquant.risk.domain.RiskDecision;
import com.kwikquant.risk.domain.RiskRuleType;
import com.kwikquant.risk.domain.RiskVerdict;
import com.kwikquant.risk.domain.RuleResult;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest(classes = KwikquantApplication.class)
@TestPropertySource(
        properties = {
            "JWT_SECRET=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
            "ENCRYPTION_KEY=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
        })
class RiskDecisionMapperTest extends AbstractIntegrationTest {

    @Autowired
    RiskDecisionMapper decisionMapper;

    @Autowired
    JdbcTemplate jdbc;

    private static String uniqueRequestId() {
        return "req-" + System.nanoTime();
    }

    @Test
    void insertAndFindByRequestId() {
        String reqId = uniqueRequestId();
        RiskDecision decision = new RiskDecision();
        decision.setRequestId(reqId);
        decision.setOrderId(100L);
        decision.setAccountId(1L);
        decision.setVerdict(RiskVerdict.APPROVED);
        decision.setRuleResults(List.of(new RuleResult(RiskRuleType.MAX_NOTIONAL, true, null)));
        decisionMapper.insert(decision);

        assertThat(decision.getId()).isNotNull();

        RiskDecision loaded = decisionMapper.findByRequestId(reqId);
        assertThat(loaded).isNotNull();
        assertThat(loaded.getOrderId()).isEqualTo(100L);
        assertThat(loaded.getAccountId()).isEqualTo(1L);
        assertThat(loaded.getVerdict()).isEqualTo(RiskVerdict.APPROVED);
        assertThat(loaded.getRuleResults()).hasSize(1);
        assertThat(loaded.getRuleResults().getFirst().ruleType()).isEqualTo(RiskRuleType.MAX_NOTIONAL);
        assertThat(loaded.getRuleResults().getFirst().passed()).isTrue();
        assertThat(loaded.getCreatedAt()).isNotNull();
    }

    @Test
    void findByOrderId() {
        String reqId = uniqueRequestId();
        long orderId = System.nanoTime() % 10_000_000L;

        RiskDecision decision = new RiskDecision();
        decision.setRequestId(reqId);
        decision.setOrderId(orderId);
        decision.setAccountId(2L);
        decision.setVerdict(RiskVerdict.REJECTED);
        decision.setRuleResults(List.of(
                new RuleResult(RiskRuleType.MAX_NOTIONAL, false, "notional 60000 USDT exceeds max 50000 USDT"),
                new RuleResult(RiskRuleType.DAILY_LOSS_LIMIT, true, null)));
        decisionMapper.insert(decision);

        RiskDecision loaded = decisionMapper.findByOrderId(orderId);
        assertThat(loaded).isNotNull();
        assertThat(loaded.getRequestId()).isEqualTo(reqId);
        assertThat(loaded.getVerdict()).isEqualTo(RiskVerdict.REJECTED);
        assertThat(loaded.getRuleResults()).hasSize(2);

        // Verify failed rule
        RuleResult failedRule = loaded.getRuleResults().stream()
                .filter(r -> !r.passed())
                .findFirst()
                .orElseThrow();
        assertThat(failedRule.ruleType()).isEqualTo(RiskRuleType.MAX_NOTIONAL);
        assertThat(failedRule.reason()).contains("exceeds max");
    }

    @Test
    void findByRequestId_returnsNull_whenNotFound() {
        assertThat(decisionMapper.findByRequestId("nonexistent-" + System.nanoTime()))
                .isNull();
    }

    @Test
    void emptyRuleResults_serialization() {
        String reqId = uniqueRequestId();
        RiskDecision decision = new RiskDecision();
        decision.setRequestId(reqId);
        decision.setOrderId(200L);
        decision.setAccountId(3L);
        decision.setVerdict(RiskVerdict.APPROVED);
        decision.setRuleResults(List.of());
        decisionMapper.insert(decision);

        RiskDecision loaded = decisionMapper.findByRequestId(reqId);
        assertThat(loaded).isNotNull();
        assertThat(loaded.getRuleResults()).isEmpty();
    }

    /** 跨账户分页:findByUserId 通过 EXISTS join exchange_accounts 校验 owner,
     *  返当前用户所有账户的决策,排除他人账户。对应风控页跨账户总览(原型 RiskPage.jsx
     *  的 data.riskAudit)。复用 RiskPolicyMapperTest.findByUserId_* 的 seed 风格。 */
    @Test
    void findByUserId_crossAccount_filtersToCurrentUser() {
        cleanOrphanDecisions();
        long userA = seedUser();
        long userB = seedUser();
        // V28 单账户不变量:同 user 同 exchange 只能一账户,跨账户用不同 exchange
        long acctA1 = seedExchangeAccount(userA, "BINANCE");
        long acctA2 = seedExchangeAccount(userA, "OKX");
        long acctB1 = seedExchangeAccount(userB, "BINANCE");

        insertDecision(100L, acctA1, RiskVerdict.APPROVED);
        insertDecision(101L, acctA2, RiskVerdict.REJECTED);
        insertDecision(102L, acctB1, RiskVerdict.APPROVED);

        List<RiskDecision> forA = decisionMapper.findByUserId(userA, null, null, null, 50, 0);
        assertThat(forA).hasSize(2);
        assertThat(forA)
                .extracting(RiskDecision::getAccountId)
                .contains(acctA1, acctA2)
                .doesNotContain(acctB1);
        assertThat(decisionMapper.countByUserId(userA, null, null, null)).isEqualTo(2);

        List<RiskDecision> forB = decisionMapper.findByUserId(userB, null, null, null, 50, 0);
        assertThat(forB).hasSize(1);
        assertThat(forB.getFirst().getAccountId()).isEqualTo(acctB1);
        assertThat(decisionMapper.countByUserId(userB, null, null, null)).isEqualTo(1);
    }

    /** 跨账户分页 + verdict 过滤:findByUserId 的 verdict 条件正常生效。 */
    @Test
    void findByUserId_withVerdictFilter_onlyRejected() {
        cleanOrphanDecisions();
        long userA = seedUser();
        long acctA1 = seedExchangeAccount(userA, "BINANCE");
        insertDecision(100L, acctA1, RiskVerdict.APPROVED);
        insertDecision(101L, acctA1, RiskVerdict.REJECTED);

        List<RiskDecision> rejected = decisionMapper.findByUserId(userA, "REJECTED", null, null, 50, 0);
        assertThat(rejected).hasSize(1);
        assertThat(rejected.getFirst().getVerdict()).isEqualTo(RiskVerdict.REJECTED);
        assertThat(decisionMapper.countByUserId(userA, "REJECTED", null, null)).isEqualTo(1);
    }

    /** 跨账户分页 + 时间范围:findByUserId 的 startTime/endTime 条件正常生效。 */
    @Test
    void findByUserId_withTimeRange_filters() {
        cleanOrphanDecisions();
        long userA = seedUser();
        long acctA1 = seedExchangeAccount(userA, "BINANCE");
        insertDecision(100L, acctA1, RiskVerdict.APPROVED);
        // created_at 默认 now();startTime=未来时间 → 应滤掉全部
        Instant future = Instant.parse("2099-01-01T00:00:00Z");
        List<RiskDecision> result = decisionMapper.findByUserId(userA, null, future, null, 50, 0);
        assertThat(result).isEmpty();
        assertThat(decisionMapper.countByUserId(userA, null, future, null)).isEqualTo(0);
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

    private long seedExchangeAccount(long userId, String exchange) {
        return jdbc.queryForObject(
                "INSERT INTO exchange_accounts (user_id, exchange, label, api_key, api_secret, nonce, key_version, paper_trading) "
                        + "VALUES (?, ?, 'test', ?, ?, ?, 1, true) RETURNING id",
                Long.class,
                userId,
                exchange,
                "key" + System.nanoTime(),
                new byte[] {1},
                new byte[] {1});
    }

    private void insertDecision(long orderId, long accountId, RiskVerdict verdict) {
        RiskDecision d = new RiskDecision();
        d.setRequestId(uniqueRequestId());
        d.setOrderId(orderId);
        d.setAccountId(accountId);
        d.setVerdict(verdict);
        d.setRuleResults(List.of(new RuleResult(RiskRuleType.MAX_NOTIONAL, verdict == RiskVerdict.APPROVED, "r")));
        decisionMapper.insert(d);
    }

    /** AbstractIntegrationTest 不回滚事务,之前测试残留的"孤儿"决策(account_id 不在
     *  exchange_accounts)会因本测试 seed 新 exchange_accounts 而被 EXISTS join 误关联到
     *  当前 user(如 account_id=1 的孤儿遇到本测试 seed 的 exchange_accounts id=1)。
     *  清理之,保证 findByUserId 只返本测试 insert 的决策。 */
    private void cleanOrphanDecisions() {
        jdbc.execute("DELETE FROM risk_decisions WHERE account_id NOT IN (SELECT id FROM exchange_accounts)");
    }
}
