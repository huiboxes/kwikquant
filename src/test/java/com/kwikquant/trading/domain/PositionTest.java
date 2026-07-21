package com.kwikquant.trading.domain;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class PositionTest {

    @Test
    void flatFactoryProducesFlatPosition() {
        Position p = Position.flat(1L, "BTC/USDT");
        assertThat(p.getAccountId()).isEqualTo(1L);
        assertThat(p.getSymbol()).isEqualTo("BTC/USDT");
        assertThat(p.getSide()).isEqualTo(Position.SIDE_FLAT);
        assertThat(p.getQty()).isEqualByComparingTo("0");
        assertThat(p.getAvgEntryPrice()).isNull();
        assertThat(p.getRealizedPnl()).isEqualByComparingTo("0");
        assertThat(p.getVersion()).isZero();
        assertThat(p.isFlat()).isTrue();
    }

    @Test
    void longPositionIsNotFlat() {
        Position p = new Position();
        p.setSide(Position.SIDE_LONG);
        p.setQty(new BigDecimal("0.5"));
        assertThat(p.isFlat()).isFalse();
    }

    @Test
    void zeroQtyIsFlatEvenWithLongSide() {
        Position p = new Position();
        p.setSide(Position.SIDE_LONG);
        p.setQty(BigDecimal.ZERO);
        assertThat(p.isFlat()).isTrue();
    }

    @Test
    void settersAndGetters() {
        Position p = new Position();
        Instant now = Instant.now();
        p.setId(1L);
        p.setAvgEntryPrice(new BigDecimal("42000"));
        p.setRealizedPnl(new BigDecimal("100"));
        p.setVersion(5L);
        p.setCreatedAt(now);
        p.setUpdatedAt(now);
        assertThat(p.getId()).isEqualTo(1L);
        assertThat(p.getAvgEntryPrice()).isEqualByComparingTo("42000");
        assertThat(p.getRealizedPnl()).isEqualByComparingTo("100");
        assertThat(p.getVersion()).isEqualTo(5L);
        assertThat(p.getCreatedAt()).isEqualTo(now);
        assertThat(p.getUpdatedAt()).isEqualTo(now);
    }

    // ---------- getUnrealizedPnl ----------

    @Test
    void unrealizedPnl_flatReturnsNull() {
        Position p = Position.flat(1L, "BTC/USDT");
        assertThat(p.getUnrealizedPnl(new BigDecimal("50000"))).isNull();
    }

    @Test
    void unrealizedPnl_markPriceNullReturnsNull() {
        Position p = longPerp("42000", "0.5", 10);
        assertThat(p.getUnrealizedPnl(null)).isNull();
    }

    @Test
    void unrealizedPnl_qtyNullReturnsNull() {
        Position p = new Position();
        p.setSide(Position.SIDE_LONG);
        p.setAvgEntryPrice(new BigDecimal("42000"));
        p.setQty(null);
        assertThat(p.getUnrealizedPnl(new BigDecimal("50000"))).isNull();
    }

    @Test
    void unrealizedPnl_avgEntryNullReturnsNull() {
        Position p = new Position();
        p.setSide(Position.SIDE_LONG);
        p.setAvgEntryPrice(null);
        p.setQty(new BigDecimal("0.5"));
        assertThat(p.getUnrealizedPnl(new BigDecimal("50000"))).isNull();
    }

    @Test
    void unrealizedPnl_longProfit() {
        // qty=0.5, avg=42000, mark=50000 → (50000-42000)*0.5 = 4000
        Position p = longPerp("42000", "0.5", 10);
        assertThat(p.getUnrealizedPnl(new BigDecimal("50000"))).isEqualByComparingTo("4000");
    }

    @Test
    void unrealizedPnl_longLoss() {
        // qty=0.5, avg=42000, mark=40000 → (40000-42000)*0.5 = -1000
        Position p = longPerp("42000", "0.5", 10);
        assertThat(p.getUnrealizedPnl(new BigDecimal("40000"))).isEqualByComparingTo("-1000");
    }

    @Test
    void unrealizedPnl_shortInverts() {
        // SHORT: diff = mark - avg; 取反 → (avg - mark) * qty
        // avg=42000, mark=40000, qty=0.5 → -(-2000)*0.5 = 1000 (空头价格跌盈利)
        Position p = shortPerp("42000", "0.5", 10);
        assertThat(p.getUnrealizedPnl(new BigDecimal("40000"))).isEqualByComparingTo("1000");
    }

    @Test
    void unrealizedPnl_shortByPositionSideInverts() {
        // side=long 但 positionSide=SHORT(双向持仓)也算空头
        Position p = new Position();
        p.setSide(Position.SIDE_LONG);
        p.setPositionSide("SHORT");
        p.setAvgEntryPrice(new BigDecimal("42000"));
        p.setQty(new BigDecimal("0.5"));
        // avg=42000, mark=40000, 空头 → 1000
        assertThat(p.getUnrealizedPnl(new BigDecimal("40000"))).isEqualByComparingTo("1000");
    }

    // ---------- getMarginBalance ----------

    @Test
    void marginBalance_flatReturnsZero() {
        Position p = Position.flat(1L, "BTC/USDT");
        assertThat(p.getMarginBalance(new BigDecimal("50000"))).isEqualByComparingTo("0");
    }

    @Test
    void marginBalance_spotFrozenZeroReturnsUnrealized() {
        // SPOT frozenAmount=0,unrealized=4000 → marginBalance=4000
        Position p = longPerp("42000", "0.5", 10); // 注意 PERP 模拟 SPOT 仅 frozenAmount=0
        p.setFrozenAmount(BigDecimal.ZERO);
        assertThat(p.getMarginBalance(new BigDecimal("50000"))).isEqualByComparingTo("4000");
    }

    @Test
    void marginBalance_perpFrozenPlusUnrealized() {
        // frozenAmount=2000(=initialMargin), unrealized=-500 → 1500
        Position p = longPerp("42000", "0.5", 10);
        p.setFrozenAmount(new BigDecimal("2000"));
        // avg=42000, mark=41000 → (41000-42000)*0.5 = -500
        assertThat(p.getMarginBalance(new BigDecimal("41000"))).isEqualByComparingTo("1500");
    }

    @Test
    void marginBalance_nullMarkReturnsFrozen() {
        Position p = longPerp("42000", "0.5", 10);
        p.setFrozenAmount(new BigDecimal("2000"));
        // markPrice=null → unrealized=null → marginBalance = frozen = 2000
        assertThat(p.getMarginBalance(null)).isEqualByComparingTo("2000");
    }

    // ---------- computeLiquidationPrice ----------

    @Test
    void liquidationPrice_leverageNullReturnsNull() {
        Position p = longPerp("42000", "0.5", null);
        assertThat(p.computeLiquidationPrice(null)).isNull();
    }

    @Test
    void liquidationPrice_avgEntryNullReturnsNull() {
        Position p = new Position();
        p.setSide(Position.SIDE_LONG);
        p.setLeverage(10);
        p.setAvgEntryPrice(null);
        assertThat(p.computeLiquidationPrice(null)).isNull();
    }

    @Test
    void liquidationPrice_longWithDefaultMmr() {
        // LONG, leverage=10, avg=42000, mmr=0.005(默认)
        // 1 - 1/10 + 0.005 = 1 - 0.1 + 0.005 = 0.905
        // liq = 42000 * 0.905 = 38010
        Position p = longPerp("42000", "0.5", 10);
        BigDecimal liq = p.computeLiquidationPrice(null);
        assertThat(liq).isEqualByComparingTo("38010");
    }

    @Test
    void liquidationPrice_shortWithDefaultMmr() {
        // SHORT, leverage=20, avg=42000, mmr=0.005(默认)
        // 1 + 1/20 - 0.005 = 1 + 0.05 - 0.005 = 1.045
        // liq = 42000 * 1.045 = 43890
        Position p = shortPerp("42000", "0.5", 20);
        BigDecimal liq = p.computeLiquidationPrice(null);
        assertThat(liq).isEqualByComparingTo("43890");
    }

    @Test
    void liquidationPrice_customMmrOverridesDefault() {
        // LONG, leverage=10, avg=42000, mmr=0.01
        // 1 - 0.1 + 0.01 = 0.91 → 42000 * 0.91 = 38220
        Position p = longPerp("42000", "0.5", 10);
        BigDecimal liq = p.computeLiquidationPrice(new BigDecimal("0.01"));
        assertThat(liq).isEqualByComparingTo("38220");
    }

    @Test
    void liquidationPrice_shortByPositionSide() {
        // side=long, positionSide=SHORT → 空头公式
        // leverage=10, avg=42000, mmr=0.005 → 1 + 0.1 - 0.005 = 1.095 → 46000? 不对
        // 重算:1 + 1/10 - 0.005 = 1 + 0.1 - 0.005 = 1.095 → 42000 * 1.095 = 45990
        Position p = new Position();
        p.setSide(Position.SIDE_LONG);
        p.setPositionSide("SHORT");
        p.setAvgEntryPrice(new BigDecimal("42000"));
        p.setLeverage(10);
        BigDecimal liq = p.computeLiquidationPrice(null);
        assertThat(liq).isEqualByComparingTo("45990");
    }

    @Test
    void liquidationPrice_leverageOne() {
        // 边界 leverage=1,LONG,avg=42000,mmr=0.005
        // 1 - 1/1 + 0.005 = 0.005 → 42000 * 0.005 = 210
        Position p = longPerp("42000", "0.5", 1);
        BigDecimal liq = p.computeLiquidationPrice(null);
        assertThat(liq).isEqualByComparingTo("210");
    }

    // ---------- helpers ----------

    private static Position longPerp(String avg, String qty, Integer leverage) {
        Position p = new Position();
        p.setSide(Position.SIDE_LONG);
        p.setPositionSide("LONG");
        p.setAvgEntryPrice(new BigDecimal(avg));
        p.setQty(new BigDecimal(qty));
        p.setLeverage(leverage);
        p.setFrozenAmount(BigDecimal.ZERO);
        return p;
    }

    private static Position shortPerp(String avg, String qty, Integer leverage) {
        Position p = new Position();
        p.setSide(Position.SIDE_SHORT);
        p.setPositionSide("SHORT");
        p.setAvgEntryPrice(new BigDecimal(avg));
        p.setQty(new BigDecimal(qty));
        p.setLeverage(leverage);
        p.setFrozenAmount(BigDecimal.ZERO);
        return p;
    }
}
