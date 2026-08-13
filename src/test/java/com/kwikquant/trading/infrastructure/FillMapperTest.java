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

    // ---------- trading-H5: realized_pnl_delta 列(SUM/UPDATE)----------

    private static final Instant SINCE = Instant.parse("2000-01-01T00:00:00Z");

    @Test
    void sumRealizedPnlDelta_noFills_returnsZero() {
        long acct = uniqueAccountId();
        assertThat(fillMapper.sumRealizedPnlDelta(acct, SINCE)).isEqualByComparingTo("0");
    }

    @Test
    void updateRealizedPnlDelta_thenSumAggregatesAcrossFills() {
        long acct = uniqueAccountId();
        long orderId = 5000L + acct;

        Fill f1 = fill(orderId, acct, UUID.randomUUID().toString());
        fillMapper.insert(f1);
        fillMapper.updateRealizedPnlDelta(f1.getId(), new BigDecimal("120"));

        Fill f2 = fill(orderId, acct, UUID.randomUUID().toString());
        fillMapper.insert(f2);
        fillMapper.updateRealizedPnlDelta(f2.getId(), new BigDecimal("-80"));

        // 120 + (-80) = 40(平仓 PnL 汇总,与 side/price/qty 无关——纯 realized_pnl_delta 列)
        assertThat(fillMapper.sumRealizedPnlDelta(acct, SINCE)).isEqualByComparingTo("40");
    }

    @Test
    void sumRealizedPnlDelta_scopedByAccountId() {
        long acctA = uniqueAccountId();
        long acctB = uniqueAccountId() + 1;
        Fill f = fill(6000L + acctA, acctA, UUID.randomUUID().toString());
        fillMapper.insert(f);
        fillMapper.updateRealizedPnlDelta(f.getId(), new BigDecimal("500"));

        assertThat(fillMapper.sumRealizedPnlDelta(acctA, SINCE)).isEqualByComparingTo("500");
        assertThat(fillMapper.sumRealizedPnlDelta(acctB, SINCE)).isEqualByComparingTo("0");
    }

    @Test
    void insert_defaultsRealizedPnlDeltaZero_whenNotBackfilled() {
        // mapper 持久化调用方明确计算的净 delta；create 初始值仅是 insert 前占位。
        long acct = uniqueAccountId();
        Fill f = fill(7000L + acct, acct, UUID.randomUUID().toString());
        fillMapper.insert(f);
        assertThat(fillMapper.sumRealizedPnlDelta(acct, SINCE)).isEqualByComparingTo("0");
    }

    @Test
    void netRealizedPnlDelta_feeAndRebateHaveExplicitSigns() {
        assertThat(Fill.netRealizedPnlDelta(new BigDecimal("100"), new BigDecimal("4")))
                .isEqualByComparingTo("96");
        assertThat(Fill.netRealizedPnlDelta(BigDecimal.ZERO, new BigDecimal("3")))
                .isEqualByComparingTo("-3");
        assertThat(Fill.netRealizedPnlDelta(new BigDecimal("100"), new BigDecimal("-2")))
                .isEqualByComparingTo("102");
    }
}
