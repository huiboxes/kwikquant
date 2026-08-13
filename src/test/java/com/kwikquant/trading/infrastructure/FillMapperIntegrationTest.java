package com.kwikquant.trading.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.kwikquant.AbstractIntegrationTest;
import com.kwikquant.KwikquantApplication;
import com.kwikquant.shared.types.OrderSide;
import com.kwikquant.trading.domain.Fill;
import java.math.BigDecimal;
import java.time.Instant;
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
class FillMapperIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    FillMapper fillMapper;

    private static long uniqueAccountId() {
        return System.nanoTime() % 10_000_000L;
    }

    private static Fill fill(
            long orderId, long accountId, OrderSide side, String price, String qty, String fee, Instant filledAt) {
        return Fill.create(
                orderId,
                accountId,
                "BTC/USDT",
                side,
                new BigDecimal(price),
                new BigDecimal(qty),
                new BigDecimal(fee),
                "USDT",
                "maker",
                UUID.randomUUID().toString(),
                filledAt);
    }

    @Test
    void countDailyWinLoss_multiDayMixed_shouldReturnCorrectTotalAndWinDays() {
        long acct = uniqueAccountId();
        Instant since = Instant.parse("2026-07-01T00:00:00Z");

        // Day 1: OPEN_SHORT side=SELL，但净 delta 只有开仓费用，不能把卖出名义额当盈利。
        Fill openShort = fill(1001, acct, OrderSide.SELL, "100", "1", "2", Instant.parse("2026-07-01T10:00:00Z"));
        openShort.setRealizedPnlDelta(new BigDecimal("-2"));
        fillMapper.insert(openShort);

        // Day 2: CLOSE_SHORT side=BUY，方向盈利 20 - fee 3 = 17。
        Fill closeShort = fill(1002, acct, OrderSide.BUY, "80", "1", "3", Instant.parse("2026-07-02T10:00:00Z"));
        closeShort.setRealizedPnlDelta(new BigDecimal("17"));
        fillMapper.insert(closeShort);

        // Day 3: BUY 开多只有手续费成本。
        Fill buy = fill(1003, acct, OrderSide.BUY, "300", "1", "5", Instant.parse("2026-07-03T10:00:00Z"));
        buy.setRealizedPnlDelta(new BigDecimal("-5"));
        fillMapper.insert(buy);

        // Day 4: SELL 平多亏损 10，返佣 2（fee=-2）后净亏 8。
        Fill sell = fill(1004, acct, OrderSide.SELL, "290", "1", "-2", Instant.parse("2026-07-04T10:00:00Z"));
        sell.setRealizedPnlDelta(new BigDecimal("-8"));
        fillMapper.insert(sell);

        var result = fillMapper.countDailyWinLoss(acct, since);

        assertThat(result.totalDays()).isEqualTo(4);
        assertThat(result.winDays()).isEqualTo(1); // 只有 Day 2 盈利
    }

    @Test
    void countDailyWinLoss_noFills_shouldReturnZeros() {
        long acct = uniqueAccountId();
        Instant since = Instant.parse("2026-07-01T00:00:00Z");

        var result = fillMapper.countDailyWinLoss(acct, since);

        assertThat(result.totalDays()).isZero();
        assertThat(result.winDays()).isZero();
    }

    @Test
    void countDailyWinLoss_singleDayProfitable_shouldReturnOneWinDay() {
        long acct = uniqueAccountId();
        Instant since = Instant.parse("2026-07-01T00:00:00Z");

        Fill profitable = fill(2001, acct, OrderSide.SELL, "500", "1", "2", Instant.parse("2026-07-05T10:00:00Z"));
        profitable.setRealizedPnlDelta(new BigDecimal("8"));
        fillMapper.insert(profitable);

        var result = fillMapper.countDailyWinLoss(acct, since);

        assertThat(result.totalDays()).isEqualTo(1);
        assertThat(result.winDays()).isEqualTo(1);
    }
}
