package com.kwikquant.account.infrastructure;

import static org.assertj.core.api.Assertions.*;

import com.kwikquant.AbstractIntegrationTest;
import com.kwikquant.KwikquantApplication;
import com.kwikquant.account.domain.PaperBalance;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.context.TestPropertySource;

/**
 * PaperBalanceMapper 集成测试(Testcontainers PostgreSQL 16)。验证 SQL 字段映射 + CAS 乐观锁 +
 * 唯一约束 + useGeneratedKeys 回填。模式对齐 {@link com.kwikquant.trading.infrastructure.PositionMapperTest}。
 *
 * <p>account_id 不加 FK(对齐 V5 orders / V7 positions 约定,下游表不引用 exchange_accounts),
 * 故测试可直接 insert paper_balances 无需预置 exchange_account 行。
 */
@SpringBootTest(classes = KwikquantApplication.class)
@TestPropertySource(
        properties = {
            "JWT_SECRET=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
            "ENCRYPTION_KEY=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
        })
class PaperBalanceMapperIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    PaperBalanceMapper paperBalanceMapper;

    private static long uniqueAccountId() {
        return System.nanoTime() % 10_000_000L;
    }

    private PaperBalance row(long acct, String currency, String free, String used, String total) {
        PaperBalance b = new PaperBalance();
        b.setAccountId(acct);
        b.setCurrency(currency);
        b.setFree(new BigDecimal(free));
        b.setUsed(new BigDecimal(used));
        b.setTotal(new BigDecimal(total));
        b.setVersion(0);
        return b;
    }

    @Test
    void insertAndFindByAccountAndCurrency() {
        long acct = uniqueAccountId();
        PaperBalance b = row(acct, "USDT", "100000", "0", "100000");
        paperBalanceMapper.insert(b);

        assertThat(b.getId()).isNotNull();
        assertThat(b.getVersion()).isZero();

        PaperBalance loaded = paperBalanceMapper.findByAccountAndCurrency(acct, "USDT");
        assertThat(loaded).isNotNull();
        assertThat(loaded.getId()).isEqualTo(b.getId());
        assertThat(loaded.getAccountId()).isEqualTo(acct);
        assertThat(loaded.getCurrency()).isEqualTo("USDT");
        assertThat(loaded.getFree()).isEqualByComparingTo("100000");
        assertThat(loaded.getUsed()).isEqualByComparingTo("0");
        assertThat(loaded.getTotal()).isEqualByComparingTo("100000");
        assertThat(loaded.getVersion()).isZero();
        assertThat(loaded.getCreatedAt()).isNotNull();
        assertThat(loaded.getUpdatedAt()).isNotNull();
    }

    @Test
    void findByAccountReturnsAllCurrencies() {
        long acct = uniqueAccountId();
        paperBalanceMapper.insert(row(acct, "USDT", "100000", "0", "100000"));
        paperBalanceMapper.insert(row(acct, "BTC", "0.5", "0", "0.5"));

        List<PaperBalance> all = paperBalanceMapper.findByAccount(acct);
        assertThat(all).hasSize(2);
    }

    @Test
    void casUpdateSuccessWhenVersionMatches() {
        long acct = uniqueAccountId();
        PaperBalance b = row(acct, "USDT", "100000", "0", "100000");
        paperBalanceMapper.insert(b);

        b.setFree(new BigDecimal("99000"));
        b.setUsed(new BigDecimal("1000"));
        assertThat(paperBalanceMapper.casUpdate(b)).isEqualTo(1);

        PaperBalance reloaded = paperBalanceMapper.findByAccountAndCurrency(acct, "USDT");
        assertThat(reloaded.getFree()).isEqualByComparingTo("99000");
        assertThat(reloaded.getUsed()).isEqualByComparingTo("1000");
        assertThat(reloaded.getVersion()).isEqualTo(1L);
    }

    @Test
    void casUpdateFailsWhenVersionStale() {
        long acct = uniqueAccountId();
        PaperBalance b = row(acct, "USDT", "100000", "0", "100000");
        paperBalanceMapper.insert(b);

        // 第一次更新成功,version 0 → 1
        b.setFree(new BigDecimal("99000"));
        paperBalanceMapper.casUpdate(b);

        // stale: 仍用 version=0,应失败返回 0
        b.setFree(new BigDecimal("98000"));
        assertThat(paperBalanceMapper.casUpdate(b)).isZero();

        PaperBalance reloaded = paperBalanceMapper.findByAccountAndCurrency(acct, "USDT");
        assertThat(reloaded.getFree()).isEqualByComparingTo("99000");
        assertThat(reloaded.getVersion()).isEqualTo(1L);
    }

    @Test
    void uniqueConstraintViolation_duplicateAccountCurrency() {
        long acct = uniqueAccountId();
        paperBalanceMapper.insert(row(acct, "USDT", "100000", "0", "100000"));

        assertThatThrownBy(() -> paperBalanceMapper.insert(row(acct, "USDT", "50000", "0", "50000")))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void deleteByAccount_removesAllRows() {
        long acct = uniqueAccountId();
        paperBalanceMapper.insert(row(acct, "USDT", "100000", "0", "100000"));
        paperBalanceMapper.insert(row(acct, "BTC", "0.5", "0", "0.5"));

        assertThat(paperBalanceMapper.deleteByAccount(acct)).isEqualTo(2);
        assertThat(paperBalanceMapper.findByAccount(acct)).isEmpty();
    }
}
