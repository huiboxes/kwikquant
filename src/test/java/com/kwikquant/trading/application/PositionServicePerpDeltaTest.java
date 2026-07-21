package com.kwikquant.trading.application;

import static org.assertj.core.api.Assertions.*;

import com.kwikquant.shared.types.MarginMode;
import com.kwikquant.shared.types.PositionEffect;
import com.kwikquant.trading.domain.Position;
import com.kwikquant.trading.domain.RejectFillException;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * Unit tests on {@link PositionService#applyPerpDelta} (pure logic, no DB).
 *
 * <p>覆盖 OPEN_LONG/OPEN_SHORT (新仓 + 加仓), CLOSE_LONG/CLOSE_SHORT (全平 + 部分平 + over-position 抛
 * {@link RejectFillException}),平仓后清零字段(side=flat + avg=null + frozen=0 + liqPrice=null)。
 */
class PositionServicePerpDeltaTest {

    private static BigDecimal bd(String s) {
        return new BigDecimal(s);
    }

    /** 构造 flat PERP 持仓(leverage/marginMode 已设,其余字段由 applyPerpDelta 填充)。 */
    private static Position flatPerp(int leverage) {
        Position p = Position.flat(1L, "BTC/USDT");
        p.setLeverage(leverage);
        p.setMarginMode(MarginMode.ISOLATED);
        return p;
    }

    /** 构造已开仓的 LONG PERP 持仓。 */
    private static Position longPerp(String qty, String avg, String frozen, int leverage) {
        Position p = flatPerp(leverage);
        p.setSide(Position.SIDE_LONG);
        p.setPositionSide("LONG");
        p.setQty(bd(qty));
        p.setAvgEntryPrice(bd(avg));
        p.setFrozenAmount(bd(frozen));
        p.setLiquidationPrice(p.computeLiquidationPrice(bd("0.005")));
        return p;
    }

    /** 构造已开仓的 SHORT PERP 持仓。 */
    private static Position shortPerp(String qty, String avg, String frozen, int leverage) {
        Position p = flatPerp(leverage);
        p.setSide(Position.SIDE_SHORT);
        p.setPositionSide("SHORT");
        p.setQty(bd(qty));
        p.setAvgEntryPrice(bd(avg));
        p.setFrozenAmount(bd(frozen));
        p.setLiquidationPrice(p.computeLiquidationPrice(bd("0.005")));
        return p;
    }

    // ---------- OPEN_LONG ----------

    @Test
    void openLongOnFlatSetsQtyAvgFrozenSidePositionSideLiq() {
        Position p = flatPerp(10);
        BigDecimal pnl = PositionService.applyPerpDelta(p, bd("0.1"), bd("42000"), PositionEffect.OPEN_LONG);
        assertThat(pnl).isEqualByComparingTo("0");
        assertThat(p.getSide()).isEqualTo(Position.SIDE_LONG);
        assertThat(p.getPositionSide()).isEqualTo("LONG");
        assertThat(p.getQty()).isEqualByComparingTo("0.1");
        assertThat(p.getAvgEntryPrice()).isEqualByComparingTo("42000");
        // initialMargin = 42000 * 0.1 / 10 = 420
        assertThat(p.getFrozenAmount()).isEqualByComparingTo("420");
        // liq = 42000 * (1 - 1/10 + 0.005) = 42000 * 0.905 = 38010
        assertThat(p.getLiquidationPrice()).isEqualByComparingTo("38010");
    }

    @Test
    void openLongAddsWithWeightedAvgAndIncrementsFrozen() {
        Position p = longPerp("0.1", "42000", "420", 10);
        BigDecimal pnl = PositionService.applyPerpDelta(p, bd("0.05"), bd("42500"), PositionEffect.OPEN_LONG);
        assertThat(pnl).isEqualByComparingTo("0");
        assertThat(p.getQty()).isEqualByComparingTo("0.15");
        // (0.1*42000 + 0.05*42500) / 0.15 = (4200 + 2125) / 0.15 = 42166.66666667
        assertThat(p.getAvgEntryPrice()).isEqualByComparingTo("42166.66666667");
        // frozen = 420 + (42500*0.05/10) = 420 + 212.5 = 632.5
        assertThat(p.getFrozenAmount()).isEqualByComparingTo("632.5");
        // liq 重算: 42166.66666667 * 0.905 = 38160.83333334
        assertThat(p.getLiquidationPrice()).isEqualByComparingTo("38160.83333334");
    }

    // ---------- OPEN_SHORT ----------

    @Test
    void openShortOnFlatSetsShortSideAndFrozen() {
        Position p = flatPerp(20);
        BigDecimal pnl = PositionService.applyPerpDelta(p, bd("0.5"), bd("42000"), PositionEffect.OPEN_SHORT);
        assertThat(pnl).isEqualByComparingTo("0");
        assertThat(p.getSide()).isEqualTo(Position.SIDE_SHORT);
        assertThat(p.getPositionSide()).isEqualTo("SHORT");
        assertThat(p.getQty()).isEqualByComparingTo("0.5");
        // initialMargin = 42000 * 0.5 / 20 = 1050
        assertThat(p.getFrozenAmount()).isEqualByComparingTo("1050");
        // liq = 42000 * (1 + 1/20 - 0.005) = 42000 * 1.045 = 43890
        assertThat(p.getLiquidationPrice()).isEqualByComparingTo("43890");
    }

    @Test
    void openShortAddsWithWeightedAvg() {
        Position p = shortPerp("0.5", "42000", "1050", 20);
        BigDecimal pnl = PositionService.applyPerpDelta(p, bd("0.5"), bd("41000"), PositionEffect.OPEN_SHORT);
        assertThat(pnl).isEqualByComparingTo("0");
        assertThat(p.getQty()).isEqualByComparingTo("1.0");
        // (0.5*42000 + 0.5*41000) / 1.0 = 41500
        assertThat(p.getAvgEntryPrice()).isEqualByComparingTo("41500");
        // frozen = 1050 + 41000*0.5/20 = 1050 + 1025 = 2075
        assertThat(p.getFrozenAmount()).isEqualByComparingTo("2075");
    }

    // ---------- CLOSE_LONG ----------

    @Test
    void closeLongFullClearResetsAllDirectionalFields() {
        Position p = longPerp("0.1", "42000", "420", 10);
        BigDecimal pnl = PositionService.applyPerpDelta(p, bd("0.1"), bd("43000"), PositionEffect.CLOSE_LONG);
        // (43000 - 42000) * 0.1 = 100
        assertThat(pnl).isEqualByComparingTo("100");
        // 全平清零
        assertThat(p.getQty()).isEqualByComparingTo("0");
        assertThat(p.getSide()).isEqualTo(Position.SIDE_FLAT);
        assertThat(p.getAvgEntryPrice()).isNull();
        assertThat(p.getLiquidationPrice()).isNull();
        assertThat(p.getFrozenAmount()).isEqualByComparingTo("0");
        assertThat(p.getPositionSide()).isNull();
        // realizedPnl += 100
        assertThat(p.getRealizedPnl()).isEqualByComparingTo("100");
    }

    @Test
    void closeLongPartialReleaseProportionalFrozenKeepsAvgSide() {
        Position p = longPerp("0.1", "42000", "420", 10);
        BigDecimal pnl = PositionService.applyPerpDelta(p, bd("0.04"), bd("43000"), PositionEffect.CLOSE_LONG);
        // (43000 - 42000) * 0.04 = 40
        assertThat(pnl).isEqualByComparingTo("40");
        assertThat(p.getQty()).isEqualByComparingTo("0.06");
        // avg 不变
        assertThat(p.getAvgEntryPrice()).isEqualByComparingTo("42000");
        // frozen = 420 - 420*0.04/0.1 = 420 - 168 = 252
        assertThat(p.getFrozenAmount()).isEqualByComparingTo("252");
        // side/positionSide 不变
        assertThat(p.getSide()).isEqualTo(Position.SIDE_LONG);
        assertThat(p.getPositionSide()).isEqualTo("LONG");
        // liquidationPrice 不变(全平才清零)
        assertThat(p.getLiquidationPrice()).isEqualByComparingTo("38010");
        // realizedPnl += 40
        assertThat(p.getRealizedPnl()).isEqualByComparingTo("40");
    }

    @Test
    void closeLongOverPositionThrowsRejectFillException() {
        Position p = longPerp("0.1", "42000", "420", 10);
        assertThatThrownBy(() -> PositionService.applyPerpDelta(p, bd("0.15"), bd("43000"), PositionEffect.CLOSE_LONG))
                .isInstanceOf(RejectFillException.class)
                .hasMessageContaining("PERP CLOSE over-position")
                .hasMessageContaining("fillQty=0.15")
                .hasMessageContaining("qty=0.1");
        // 状态不变(异常前不修改 p)
        assertThat(p.getQty()).isEqualByComparingTo("0.1");
        assertThat(p.getFrozenAmount()).isEqualByComparingTo("420");
    }

    @Test
    void closeLongOnFlatThrowsRejectFillException() {
        Position p = flatPerp(10);
        assertThatThrownBy(() -> PositionService.applyPerpDelta(p, bd("0.1"), bd("43000"), PositionEffect.CLOSE_LONG))
                .isInstanceOf(RejectFillException.class)
                .hasMessageContaining("qty=0");
    }

    // ---------- CLOSE_SHORT ----------

    @Test
    void closeShortFullClearResetsAllDirectionalFields() {
        Position p = shortPerp("0.5", "42000", "1050", 20);
        BigDecimal pnl = PositionService.applyPerpDelta(p, bd("0.5"), bd("41000"), PositionEffect.CLOSE_SHORT);
        // (avg - fill) * qty = (42000 - 41000) * 0.5 = 500
        assertThat(pnl).isEqualByComparingTo("500");
        assertThat(p.getQty()).isEqualByComparingTo("0");
        assertThat(p.getSide()).isEqualTo(Position.SIDE_FLAT);
        assertThat(p.getAvgEntryPrice()).isNull();
        assertThat(p.getLiquidationPrice()).isNull();
        assertThat(p.getFrozenAmount()).isEqualByComparingTo("0");
        assertThat(p.getPositionSide()).isNull();
        assertThat(p.getRealizedPnl()).isEqualByComparingTo("500");
    }

    @Test
    void closeShortPartialReleaseProportionalFrozenKeepsAvgSide() {
        Position p = shortPerp("0.5", "42000", "1050", 20);
        BigDecimal pnl = PositionService.applyPerpDelta(p, bd("0.2"), bd("41000"), PositionEffect.CLOSE_SHORT);
        // (42000 - 41000) * 0.2 = 200
        assertThat(pnl).isEqualByComparingTo("200");
        assertThat(p.getQty()).isEqualByComparingTo("0.3");
        assertThat(p.getAvgEntryPrice()).isEqualByComparingTo("42000");
        // frozen = 1050 - 1050*0.2/0.5 = 1050 - 420 = 630
        assertThat(p.getFrozenAmount()).isEqualByComparingTo("630");
        assertThat(p.getSide()).isEqualTo(Position.SIDE_SHORT);
        assertThat(p.getPositionSide()).isEqualTo("SHORT");
        assertThat(p.getRealizedPnl()).isEqualByComparingTo("200");
    }

    @Test
    void closeShortOverPositionThrowsRejectFillException() {
        Position p = shortPerp("0.5", "42000", "1050", 20);
        assertThatThrownBy(() -> PositionService.applyPerpDelta(p, bd("0.6"), bd("41000"), PositionEffect.CLOSE_SHORT))
                .isInstanceOf(RejectFillException.class)
                .hasMessageContaining("fillQty=0.6")
                .hasMessageContaining("qty=0.5");
    }
}
