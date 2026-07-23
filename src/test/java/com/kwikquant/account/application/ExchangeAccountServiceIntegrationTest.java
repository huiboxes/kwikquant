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
 * ExchangeAccountService 集成测试(Testcontainers PostgreSQL 16)。验证模拟盘账户创建真 insert
 * 到 DB(api_secret/nonce/api_key 已放宽为 NULLABLE,模拟盘存真 NULL)+ initBalance 初始化 10 万 USDT。
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
        ExchangeAccount account = service.create(
                new CreateAccountCommand(userId, Exchange.BINANCE, "paper1", null, null, null, true, false));

        // create 成功返回(没抛 DataIntegrityViolationException)= insert 满足 NOT NULL/CHECK 约束
        assertThat(account.getId()).isNotNull();
        assertThat(account.isPaperTrading()).isTrue();
        assertThat(account.getExchange()).isEqualTo(Exchange.BINANCE);

        // 重读 DB 验证行 + nonce/api_secret/api_key 为真 NULL(模拟盘不该有"密文"概念)
        ExchangeAccount loaded = accountMapper.findById(account.getId());
        assertThat(loaded.getExchange()).isEqualTo(Exchange.BINANCE);
        assertThat(loaded.getApiKey()).isNull();
        assertThat(loaded.getNonce()).isNull();
        assertThat(loaded.getApiSecret()).isNull();
        assertThat(loaded.isPaperTrading()).isTrue();

        // initBalance 调了 → paper_balances 有 10 万 USDT 行
        PaperBalance usdt = paperBalanceMapper.findByAccountAndCurrency(account.getId(), "USDT");
        assertThat(usdt).isNotNull();
        assertThat(usdt.getFree()).isEqualByComparingTo("100000");
        assertThat(usdt.getUsed()).isEqualByComparingTo("0");
        assertThat(usdt.getTotal()).isEqualByComparingTo("100000");
    }

    @Test
    void create_paperAccount_rejectsExchangePaper() {
        long userId = seedUser();
        assertThatThrownBy(() -> service.create(
                        new CreateAccountCommand(userId, Exchange.PAPER, "x", null, null, null, true, false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PAPER");
    }

    @Test
    void create_realExchange_encryptsCredentials() {
        long userId = seedUser();
        ExchangeAccount account = service.create(new CreateAccountCommand(
                userId, Exchange.BINANCE, "real", "api-key-1", "secret-xyz", "pass", false, false));

        assertThat(account.isPaperTrading()).isFalse();
        // 真实账户加密:nonce 非空(16 字节),apiSecret 非空(加密后)
        assertThat(account.getNonce()).isNotNull();
        assertThat(account.getNonce().length).isGreaterThan(0);
        assertThat(account.getApiSecret()).isNotNull();
        assertThat(account.getApiSecret().length).isGreaterThan(0);
        // 不初始化 paper_balances(真实账户余额由交易所维护)
        assertThat(paperBalanceMapper.findByAccount(account.getId())).isEmpty();
    }
}
