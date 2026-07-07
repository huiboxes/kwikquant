package com.kwikquant.account.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kwikquant.AbstractIntegrationTest;
import com.kwikquant.KwikquantApplication;
import com.kwikquant.account.domain.ExchangeAccount;
import com.kwikquant.account.domain.PaperBalance;
import com.kwikquant.account.domain.User;
import com.kwikquant.account.infrastructure.ExchangeAccountMapper;
import com.kwikquant.account.infrastructure.PaperBalanceMapper;
import com.kwikquant.account.infrastructure.UserMapper;
import com.kwikquant.shared.types.Exchange;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * ExchangeAccountService 集成测试(Testcontainers PostgreSQL 16)。验证 PAPER 账户创建真 insert
 * 到 DB(NOT NULL 约束:nonce/api_secret)+ initBalance 初始化 10 万 USDT。防 nonce=null 回归
 * (Batch 4 曾 setNonce(null) 违反 NOT NULL,单元测试 mock mapper 漏测)。
 */
@SpringBootTest(classes = KwikquantApplication.class)
@TestPropertySource(
        properties = {
            "JWT_SECRET=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
            "ENCRYPTION_KEY=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
        })
class ExchangeAccountServiceIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    ExchangeAccountService service;

    @Autowired
    UserMapper userMapper;

    @Autowired
    ExchangeAccountMapper accountMapper;

    @Autowired
    PaperBalanceMapper paperBalanceMapper;

    /** 预置 user(exchange_accounts.user_id FK 引用 users)。 */
    private long seedUser() {
        User u = new User();
        u.setUsername("test-" + System.nanoTime());
        u.setEmail("test-" + System.nanoTime() + "@kwikquant.io");
        u.setPasswordHash("x");
        u.setEnabled(true);
        userMapper.insert(u);
        return u.getId();
    }

    @Test
    void create_paperAccount_insertsRowAndInitsBalance() {
        long userId = seedUser();
        ExchangeAccount account =
                service.create(userId, Exchange.PAPER, "paper1", "paper-key", "paper-secret", null, Exchange.BINANCE);

        // create 成功返回(没抛 DataIntegrityViolationException)= insert 满足 NOT NULL(nonce/api_secret)
        assertThat(account.getId()).isNotNull();
        assertThat(account.isPaperTrading()).isTrue();
        assertThat(account.getReferenceExchange()).isEqualTo(Exchange.BINANCE);

        // 重读 DB 验证行 + nonce/api_secret 非空(防 nonce=null 回归)
        ExchangeAccount loaded = accountMapper.findById(account.getId());
        assertThat(loaded.getExchange()).isEqualTo(Exchange.PAPER);
        assertThat(loaded.getNonce()).isNotNull();
        assertThat(loaded.getNonce()).isEmpty(); // PAPER 跳过加密,空 byte[]
        assertThat(loaded.getApiSecret()).isNotNull();
        assertThat(loaded.getApiSecret()).isEmpty();
        assertThat(loaded.isPaperTrading()).isTrue();
        assertThat(loaded.getReferenceExchange()).isEqualTo(Exchange.BINANCE);

        // initBalance 调了 → paper_balances 有 10 万 USDT 行
        PaperBalance usdt = paperBalanceMapper.findByAccountAndCurrency(account.getId(), "USDT");
        assertThat(usdt).isNotNull();
        assertThat(usdt.getFree()).isEqualByComparingTo("100000");
        assertThat(usdt.getUsed()).isEqualByComparingTo("0");
        assertThat(usdt.getTotal()).isEqualByComparingTo("100000");
    }

    @Test
    void create_paperWithoutReferenceExchange_throws() {
        long userId = seedUser();
        assertThatThrownBy(() -> service.create(userId, Exchange.PAPER, "x", "k", "s", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("referenceExchange");
    }

    @Test
    void create_realExchange_referenceExchangeNullAndEncrypts() {
        long userId = seedUser();
        ExchangeAccount account =
                service.create(userId, Exchange.BINANCE, "real", "api-key-1", "secret-xyz", "pass", null);

        assertThat(account.getReferenceExchange()).isNull();
        assertThat(account.isPaperTrading()).isFalse(); // 修硬编码 bug:真实账户 false
        // 真实账户加密:nonce 非空(16 字节),apiSecret 非空(加密后)
        assertThat(account.getNonce()).isNotNull();
        assertThat(account.getNonce().length).isGreaterThan(0);
        assertThat(account.getApiSecret()).isNotNull();
        assertThat(account.getApiSecret().length).isGreaterThan(0);
        // 不初始化 paper_balances(真实账户余额由交易所维护)
        assertThat(paperBalanceMapper.findByAccount(account.getId())).isEmpty();
    }
}
