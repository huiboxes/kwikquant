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

        // Day 1 (July 1): SELL 100@1 - fee 0 → net = 100 (profit)
        fillMapper.insert(fill(1001, acct, OrderSide.SELL, "100", "1", "0", Instant.parse("2026-07-01T10:00:00Z")));

        // Day 2 (July 2): BUY 200@1 + fee 10 → net = -(200+10) = -210 (loss)
        fillMapper.insert(fill(1002, acct, OrderSide.BUY, "200", "1", "10", Instant.parse("2026-07-02T10:00:00Z")));

        // Day 3 (July 3): SELL 300@1 - fee 5 → net = 295 (profit)
        fillMapper.insert(fill(1003, acct, OrderSide.SELL, "300", "1", "5", Instant.parse("2026-07-03T10:00:00Z")));

        // Day 4 (July 4): BUY 50@1 + fee 0 → net = -50 (loss)
        fillMapper.insert(fill(1004, acct, OrderSide.BUY, "50", "1", "0", Instant.parse("2026-07-04T10:00:00Z")));

        var result = fillMapper.countDailyWinLoss(acct, since);

        assertThat(result.totalDays()).isEqualTo(4);
        assertThat(result.winDays()).isEqualTo(2); // Day 1 and Day 3 are profitable
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

        // Single SELL on July 5: net = 500 - 2 = 498 (profit)
        fillMapper.insert(fill(2001, acct, OrderSide.SELL, "500", "1", "2", Instant.parse("2026-07-05T10:00:00Z")));

        var result = fillMapper.countDailyWinLoss(acct, since);

        assertThat(result.totalDays()).isEqualTo(1);
        assertThat(result.winDays()).isEqualTo(1);
    }
}
