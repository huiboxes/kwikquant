package com.kwikquant.trading.application;

import static org.assertj.core.api.Assertions.*;

import com.kwikquant.shared.types.OrderSide;
import com.kwikquant.trading.domain.Position;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** Unit tests on PositionService.applyDelta (pure logic, no DB). */
class PositionServiceDeltaTest {

    private static Position flat() {
        return Position.flat(1L, "BTC/USDT");
    }

    private static Position withSideAndQty(String side, String qty, String avg) {
        Position p = flat();
        p.setSide(side);
        p.setQty(new BigDecimal(qty));
        p.setAvgEntryPrice(avg == null ? null : new BigDecimal(avg));
        p.setRealizedPnl(BigDecimal.ZERO);
        return p;
    }

    private static BigDecimal bd(String s) {
        return new BigDecimal(s);
    }

    @Test
    void flatPlusBuyBecomesLong() {
        Position p = flat();
        PositionService.applyDelta(p, OrderSide.BUY, bd("0.1"), bd("42000"), bd("4.2"));
        assertThat(p.getSide()).isEqualTo(Position.SIDE_LONG);
        assertThat(p.getQty()).isEqualByComparingTo("0.1");
        assertThat(p.getAvgEntryPrice()).isEqualByComparingTo("42000");
        assertThat(p.getRealizedPnl()).isEqualByComparingTo("-4.2");
    }

    @Test
    void flatPlusSellBecomesShort() {
        Position p = flat();
        PositionService.applyDelta(p, OrderSide.SELL, bd("0.1"), bd("42000"), bd("4.2"));
        assertThat(p.getSide()).isEqualTo(Position.SIDE_SHORT);
        assertThat(p.getQty()).isEqualByComparingTo("0.1");
    }

    @Test
    void longPlusBuyAddsWithWeightedAvg() {
        Position p = withSideAndQty(Position.SIDE_LONG, "0.1", "42000");
        PositionService.applyDelta(p, OrderSide.BUY, bd("0.05"), bd("42500"), bd("2.1"));
        assertThat(p.getQty()).isEqualByComparingTo("0.15");
        // (0.1*42000 + 0.05*42500) / 0.15 = (4200 + 2125) / 0.15 = 42166.66666667
        assertThat(p.getAvgEntryPrice()).isEqualByComparingTo("42166.66666667");
        assertThat(p.getRealizedPnl()).isEqualByComparingTo("-2.1");
    }

    @Test
    void longPlusPartialSellReducesQtyAndRealizesPnl() {
        Position p = withSideAndQty(Position.SIDE_LONG, "0.1", "42000");
        PositionService.applyDelta(p, OrderSide.SELL, bd("0.04"), bd("43000"), bd("1.5"));
        assertThat(p.getQty()).isEqualByComparingTo("0.06");
        assertThat(p.getSide()).isEqualTo(Position.SIDE_LONG);
        // 0.04 * (43000 - 42000) - 1.5 = 40 - 1.5 = 38.5
        assertThat(p.getRealizedPnl()).isEqualByComparingTo("38.5");
    }

    @Test
    void longPlusFullSellBecomesFlat() {
        Position p = withSideAndQty(Position.SIDE_LONG, "0.1", "42000");
        PositionService.applyDelta(p, OrderSide.SELL, bd("0.1"), bd("43000"), bd("4.3"));
        assertThat(p.getQty()).isEqualByComparingTo("0");
        assertThat(p.getSide()).isEqualTo(Position.SIDE_FLAT);
        assertThat(p.getAvgEntryPrice()).isNull();
        // 0.1 * (43000 - 42000) - 4.3 = 100 - 4.3 = 95.7
        assertThat(p.getRealizedPnl()).isEqualByComparingTo("95.7");
    }

    @Test
    void longPlusLargerSellFlipsToShort() {
        Position p = withSideAndQty(Position.SIDE_LONG, "0.1", "42000");
        PositionService.applyDelta(p, OrderSide.SELL, bd("0.15"), bd("43000"), bd("6.45"));
        // close 0.1 long @42000 → 43000: pnl = 0.1*1000 = 100; remain 0.05 → short @43000
        assertThat(p.getSide()).isEqualTo(Position.SIDE_SHORT);
        assertThat(p.getQty()).isEqualByComparingTo("0.05");
        assertThat(p.getAvgEntryPrice()).isEqualByComparingTo("43000");
        // realized = 100 - 6.45 = 93.55
        assertThat(p.getRealizedPnl()).isEqualByComparingTo("93.55");
    }

    @Test
    void shortPlusPartialBuyReducesQtyAndRealizesPnl() {
        Position p = withSideAndQty(Position.SIDE_SHORT, "0.1", "42000");
        PositionService.applyDelta(p, OrderSide.BUY, bd("0.04"), bd("41000"), bd("1.5"));
        assertThat(p.getQty()).isEqualByComparingTo("0.06");
        assertThat(p.getSide()).isEqualTo(Position.SIDE_SHORT);
        // 0.04 * (42000 - 41000) - 1.5 = 40 - 1.5 = 38.5
        assertThat(p.getRealizedPnl()).isEqualByComparingTo("38.5");
    }

    @Test
    void shortPlusLargerBuyFlipsToLong() {
        Position p = withSideAndQty(Position.SIDE_SHORT, "0.1", "42000");
        PositionService.applyDelta(p, OrderSide.BUY, bd("0.15"), bd("41000"), bd("6.15"));
        assertThat(p.getSide()).isEqualTo(Position.SIDE_LONG);
        assertThat(p.getQty()).isEqualByComparingTo("0.05");
        assertThat(p.getAvgEntryPrice()).isEqualByComparingTo("41000");
        // 0.1 * (42000 - 41000) - 6.15 = 100 - 6.15 = 93.85
        assertThat(p.getRealizedPnl()).isEqualByComparingTo("93.85");
    }
}
