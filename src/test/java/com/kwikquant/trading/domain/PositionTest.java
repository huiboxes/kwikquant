package com.kwikquant.trading.domain;

import static org.assertj.core.api.Assertions.*;

import com.kwikquant.shared.types.MarginMode;
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
        p.setOpenedAt(now);
        p.setCreatedAt(now);
        p.setUpdatedAt(now);
        assertThat(p.getId()).isEqualTo(1L);
        assertThat(p.getAvgEntryPrice()).isEqualByComparingTo("42000");
        assertThat(p.getRealizedPnl()).isEqualByComparingTo("100");
        assertThat(p.getVersion()).isEqualTo(5L);
        assertThat(p.getOpenedAt()).isEqualTo(now);
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
        // LONG margin-aware, avg=42000, qty=0.5, margin=2100(10x), mmr=0.005(默认)
        // liq = (42000×0.5 − 2100) / (0.5×0.995) = 18900/0.4975 = 37989.94974874
        Position p = longPerp("42000", "0.5", 10);
        p.setFrozenAmount(new BigDecimal("2100"));
        BigDecimal liq = p.computeLiquidationPrice(null);
        assertThat(liq).isEqualByComparingTo("37989.94974874");
    }

    @Test
    void liquidationPrice_shortWithDefaultMmr() {
        // SHORT margin-aware, avg=42000, qty=0.5, margin=1050(20x), mmr=0.005(默认)
        // liq = (42000×0.5 + 1050) / (0.5×1.005) = 22050/0.5025 = 43880.59701493
        Position p = shortPerp("42000", "0.5", 20);
        p.setFrozenAmount(new BigDecimal("1050"));
        BigDecimal liq = p.computeLiquidationPrice(null);
        assertThat(liq).isEqualByComparingTo("43880.59701493");
    }

    @Test
    void liquidationPrice_customMmrOverridesDefault() {
        // LONG, avg=42000, qty=0.5, margin=2100, mmr=0.01
        // liq = (21000 − 2100) / (0.5×0.99) = 18900/0.495 = 38181.81818182
        Position p = longPerp("42000", "0.5", 10);
        p.setFrozenAmount(new BigDecimal("2100"));
        BigDecimal liq = p.computeLiquidationPrice(new BigDecimal("0.01"));
        assertThat(liq).isEqualByComparingTo("38181.81818182");
    }

    @Test
    void computeLiquidationPrice_cross_returnsNull() {
        // CROSS 全仓无单仓强平价(账户级 marginRatio 判定,见 PaperExecutor.checkLiquidation CROSS 分支)
        Position p = longPerp("42000", "0.5", 10);
        p.setMarginMode(MarginMode.CROSS);
        assertThat(p.computeLiquidationPrice(null)).isNull();
    }

    @Test
    void computeLiquidationPrice_isolated_returnsPrice() {
        // ISOLATED 走 margin-aware 逐仓公式(marginMode==null 同行为,向后兼容)
        Position p = longPerp("42000", "0.5", 10);
        p.setMarginMode(MarginMode.ISOLATED);
        p.setFrozenAmount(new BigDecimal("2100"));
        assertThat(p.computeLiquidationPrice(null)).isEqualByComparingTo("37989.94974874");
    }

    @Test
    void liquidationPrice_shortByPositionSide() {
        // side=long, positionSide=SHORT(双向持仓)→ 空头公式
        // avg=42000, qty=0.5, margin=2100, mmr=0.005 → (21000+2100)/(0.5×1.005) = 23100/0.5025 = 45970.14925373
        Position p = new Position();
        p.setSide(Position.SIDE_LONG);
        p.setPositionSide("SHORT");
        p.setAvgEntryPrice(new BigDecimal("42000"));
        p.setQty(new BigDecimal("0.5"));
        p.setLeverage(10);
        p.setFrozenAmount(new BigDecimal("2100"));
        BigDecimal liq = p.computeLiquidationPrice(null);
        assertThat(liq).isEqualByComparingTo("45970.14925373");
    }

    @Test
    void liquidationPrice_fullMarginIsZero() {
        // 边界:1x 全额保证金(margin=notional=21000),LONG → (21000−21000)/0.4975 = 0
        // 强平价恰为 0 → 价格比较永不触发,触发判定必须走 marginBreached(spec §3.6)
        Position p = longPerp("42000", "0.5", 1);
        p.setFrozenAmount(new BigDecimal("21000"));
        BigDecimal liq = p.computeLiquidationPrice(null);
        assertThat(liq).isEqualByComparingTo("0");
    }

    @Test
    void liquidationPrice_marginErodedNegativeMovesAboveAvg() {
        // 资金费把保证金侵蚀穿仓(margin=−100),LONG → (21000+100)/0.4975 = 42412.06030151
        // 强平价高于开仓均价=当前市价已在触发区(margin-aware 才看得见,杠杆式公式不动)
        Position p = longPerp("42000", "0.5", 10);
        p.setFrozenAmount(new BigDecimal("-100"));
        BigDecimal liq = p.computeLiquidationPrice(null);
        assertThat(liq).isEqualByComparingTo("42412.06030151");
    }

    @Test
    void liquidationPrice_qtyZeroOrNullReturnsNull() {
        // flat(qty=0)与 qty 缺失:margin-aware 公式分母含 qty,返 null 不判
        Position flat = longPerp("42000", "0.5", 10);
        flat.setQty(BigDecimal.ZERO);
        assertThat(flat.computeLiquidationPrice(null)).isNull();
        Position noQty = longPerp("42000", "0.5", 10);
        noQty.setQty(null);
        assertThat(noQty.computeLiquidationPrice(null)).isNull();
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
