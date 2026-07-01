package com.kwikquant.trading.infrastructure;

import static org.assertj.core.api.Assertions.*;

import com.kwikquant.AbstractIntegrationTest;
import com.kwikquant.KwikquantApplication;
import com.kwikquant.shared.types.OrderSide;
import com.kwikquant.trading.domain.Fill;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
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
class FillMapperTest extends AbstractIntegrationTest {

    @Autowired
    FillMapper fillMapper;

    private static long uniqueAccountId() {
        return System.nanoTime() % 10_000_000L;
    }

    private static Fill fill(long orderId, long accountId, String externalFillId) {
        return Fill.create(
                orderId,
                accountId,
                "BTC/USDT",
                OrderSide.BUY,
                new BigDecimal("42000"),
                new BigDecimal("0.1"),
                new BigDecimal("4.2"),
                "USDT",
                "maker",
                externalFillId,
                Instant.parse("2026-06-30T00:00:00Z"));
    }

    @Test
    void insertAndFindByOrderId() {
        long acct = uniqueAccountId();
        long orderId = 1000 + acct;
        Fill f1 = fill(orderId, acct, UUID.randomUUID().toString());
        Fill f2 = fill(orderId, acct, UUID.randomUUID().toString());
        fillMapper.insert(f1);
        fillMapper.insert(f2);

        List<Fill> all = fillMapper.findByOrderId(orderId);
        assertThat(all).hasSize(2);
    }

    @Test
    void existsByExternalFillId_trueAfterInsert() {
        long acct = uniqueAccountId();
        String ext = UUID.randomUUID().toString();
        assertThat(fillMapper.existsByExternalFillId(acct, ext)).isFalse();
        fillMapper.insert(fill(2000L + acct, acct, ext));
        assertThat(fillMapper.existsByExternalFillId(acct, ext)).isTrue();
    }

    @Test
    void existsByExternalFillId_perAccountScoped() {
        long acctA = uniqueAccountId();
        long acctB = uniqueAccountId() + 1;
        String ext = UUID.randomUUID().toString();
        fillMapper.insert(fill(3000L, acctA, ext));
        assertThat(fillMapper.existsByExternalFillId(acctA, ext)).isTrue();
        assertThat(fillMapper.existsByExternalFillId(acctB, ext)).isFalse();
    }
}
