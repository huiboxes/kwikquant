package com.kwikquant.trading.application;

import static org.assertj.core.api.Assertions.*;

import com.kwikquant.AbstractIntegrationTest;
import com.kwikquant.KwikquantApplication;
import com.kwikquant.shared.types.MarginMode;
import com.kwikquant.shared.types.MarketType;
import com.kwikquant.shared.types.OrderSide;
import com.kwikquant.shared.types.PositionEffect;
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
                acct,
                "BTC/USDT",
                OrderSide.SELL,
                new BigDecimal("0.1"),
                new BigDecimal("43000"),
                new BigDecimal("4.3"));

        var positions = positionService.findByAccount(acct);
        assertThat(positions).hasSize(1);
        Position p = positions.get(0);
        assertThat(p.getQty()).isEqualByComparingTo("0");
        assertThat(p.getRealizedPnl()).isEqualByComparingTo("91.5");
    }

    @Test
    void perpBothSidesFullCloseNoUniqueIndexCollision() {
        // V38 唯一索引按 COALESCE(position_side,'LONG') 折叠:全平置 null 会让 flat SHORT 行撞
        // flat LONG 行键(DuplicateKeyException 无捕获 → 强平/平仓事务回滚死循环)。
        // 修复后全平保留 positionSide:LONG 全平 → 开 SHORT → SHORT 全平,三行操作全落库。
        long acct = uniqueAccountId();
        String sym = "ETH/USDT:USDT";
        positionService.applyFill(
                acct,
                sym,
                OrderSide.BUY,
                new BigDecimal("0.5"),
                new BigDecimal("3000"),
                new BigDecimal("1.5"),
                MarketType.PERP,
                PositionEffect.OPEN_LONG,
                10,
                MarginMode.ISOLATED);
        assertThat(positionService.findByAccount(acct).stream()
                        .filter(x -> "LONG".equals(x.getPositionSide()))
                        .findFirst()
                        .orElseThrow()
                        .getOpenedAt())
                .isNotNull();
        positionService.applyFill(
                acct,
                sym,
                OrderSide.SELL,
                new BigDecimal("0.5"),
                new BigDecimal("3100"),
                new BigDecimal("1.5"),
                MarketType.PERP,
                PositionEffect.CLOSE_LONG,
                10,
                MarginMode.ISOLATED);
        // opened_at DB 往返:开仓簿记非空(catch-up 下界),全平清空(重开重新簿记)——
        // 映射漏列会让资金费 catch-up 下界丢失,flat 期历史期次被错误回收
        Position closedLong = positionService.findByAccount(acct).stream()
                .filter(x -> "LONG".equals(x.getPositionSide()))
                .findFirst()
                .orElseThrow();
        assertThat(closedLong.getOpenedAt()).isNull();
        positionService.applyFill(
                acct,
                sym,
                OrderSide.SELL,
                new BigDecimal("0.5"),
                new BigDecimal("3100"),
                new BigDecimal("1.5"),
                MarketType.PERP,
                PositionEffect.OPEN_SHORT,
                10,
                MarginMode.ISOLATED);
        // 修复前:此笔全平把 SHORT 行 positionSide 置 null → 撞 flat LONG 行索引键 → DuplicateKeyException
        positionService.applyFill(
                acct,
                sym,
                OrderSide.BUY,
                new BigDecimal("0.5"),
                new BigDecimal("3000"),
                new BigDecimal("1.5"),
                MarketType.PERP,
                PositionEffect.CLOSE_SHORT,
                10,
                MarginMode.ISOLATED);

        var positions = positionService.findByAccount(acct);
        assertThat(positions).hasSize(2); // LONG 桶行 + SHORT 桶行,各自 flat
        for (Position p : positions) {
            assertThat(p.isFlat()).isTrue();
            assertThat(p.getPositionSide()).isIn("LONG", "SHORT"); // 桶身份保留,不再折叠 null
        }
    }
}
