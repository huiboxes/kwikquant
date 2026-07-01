package com.kwikquant.trading.application;

import static org.assertj.core.api.Assertions.*;

import com.kwikquant.AbstractIntegrationTest;
import com.kwikquant.KwikquantApplication;
import com.kwikquant.shared.types.OrderSide;
import com.kwikquant.trading.domain.Position;
import java.math.BigDecimal;
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
class PositionServiceIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    PositionService positionService;

    private static long uniqueAccountId() {
        return System.nanoTime() % 10_000_000L;
    }

    @Test
    void applyFillCreatesNewPosition() {
        long acct = uniqueAccountId();
        positionService.applyFill(
                acct, "BTC/USDT", OrderSide.BUY, new BigDecimal("0.1"), new BigDecimal("42000"), new BigDecimal("4.2"));

        var positions = positionService.findByAccount(acct);
        assertThat(positions).hasSize(1);
        Position p = positions.get(0);
        assertThat(p.getSide()).isEqualTo(Position.SIDE_LONG);
        assertThat(p.getQty()).isEqualByComparingTo("0.1");
        assertThat(p.getAvgEntryPrice()).isEqualByComparingTo("42000");
    }

    @Test
    void applyFillCumulativeAddsAndUpdates() {
        long acct = uniqueAccountId();
        positionService.applyFill(
                acct, "ETH/USDT", OrderSide.BUY, new BigDecimal("1.0"), new BigDecimal("3000"), new BigDecimal("3"));
        positionService.applyFill(
                acct, "ETH/USDT", OrderSide.BUY, new BigDecimal("0.5"), new BigDecimal("3100"), new BigDecimal("1.55"));

        var positions = positionService.findByAccount(acct);
        assertThat(positions).hasSize(1);
        Position p = positions.get(0);
        // (1.0*3000 + 0.5*3100) / 1.5 = (3000 + 1550) / 1.5 = 3033.33333333
        assertThat(p.getQty()).isEqualByComparingTo("1.5");
        assertThat(p.getAvgEntryPrice()).isEqualByComparingTo("3033.33333333");
        assertThat(p.getVersion()).isEqualTo(1L);
    }

    @Test
    void applyFillSellCreatesShortPosition() {
        long acct = uniqueAccountId();
        positionService.applyFill(
                acct, "BTC/USDT", OrderSide.SELL, new BigDecimal("0.5"), new BigDecimal("42000"), new BigDecimal("21"));

        var positions = positionService.findByAccount(acct);
        assertThat(positions).hasSize(1);
        Position p = positions.get(0);
        assertThat(p.getSide()).isEqualTo(Position.SIDE_SHORT);
        assertThat(p.getQty()).isEqualByComparingTo("0.5");
        assertThat(p.getRealizedPnl()).isEqualByComparingTo("-21"); // fee negated
    }

    @Test
    void applyFillReverseClose_realizesPnl() {
        long acct = uniqueAccountId();
        // Open LONG 0.1 @ 42000
        positionService.applyFill(
                acct, "BTC/USDT", OrderSide.BUY, new BigDecimal("0.1"), new BigDecimal("42000"), new BigDecimal("4.2"));
        // Close with SELL 0.1 @ 43000 → PnL = -openFee + (43000-42000)*0.1 - closeFee = -4.2 + 100 - 4.3 = 91.5
        positionService.applyFill(
                acct, "BTC/USDT", OrderSide.SELL, new BigDecimal("0.1"), new BigDecimal("43000"), new BigDecimal("4.3"));

        var positions = positionService.findByAccount(acct);
        assertThat(positions).hasSize(1);
        Position p = positions.get(0);
        assertThat(p.getQty()).isEqualByComparingTo("0");
        assertThat(p.getRealizedPnl()).isEqualByComparingTo("91.5");
    }
}
