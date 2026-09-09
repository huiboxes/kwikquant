package com.kwikquant.trading.application;

import static org.assertj.core.api.Assertions.*;

import com.kwikquant.shared.types.MarginMode;
import com.kwikquant.shared.types.PositionEffect;
import com.kwikquant.trading.domain.Position;
import com.kwikquant.trading.domain.RejectFillException;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Unit tests on {@link PositionService#applyPerpDelta} (pure logic, no DB).
 *
 * <p>覆盖 OPEN_LONG/OPEN_SHORT (新仓 + 加仓), CLOSE_LONG/CLOSE_SHORT (全平 + 部分平 + over-position 抛
 * {@link RejectFillException}),平仓后清零字段(side=flat + avg=null + frozen=0 + liqPrice=null +
 * openedAt=null),openedAt 簿记(flat→open 写入,加仓/部分平不变,全平清空)。
 */
class PositionServicePerpDeltaTest {

    /** helper 已开仓持仓的固定首开时刻(openedAt 不变性断言用)。 */
    private static final Instant OPENED_AT = Instant.parse("2026-08-01T08:00:00Z");

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
        p.setOpenedAt(OPENED_AT);
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
        p.setOpenedAt(OPENED_AT);
        return p;
    }

    // ---------- OPEN_LONG ----------

    @Test
    void openLongOnFlatSetsQtyAvgFrozenSidePositionSideLiq() {
        Position p = flatPerp(10);
        PositionService.PerpFillOutcome out =
                PositionService.applyPerpDelta(p, bd("0.1"), bd("42000"), PositionEffect.OPEN_LONG);
        BigDecimal pnl = out.realizedPnlDelta();
        assertThat(pnl).isEqualByComparingTo("0");
        // marginDelta = +initialMargin(余额侧 ISOLATED 按此锁定保证金)
        assertThat(out.marginDelta()).isEqualByComparingTo("420");
        assertThat(p.getSide()).isEqualTo(Position.SIDE_LONG);
        assertThat(p.getPositionSide()).isEqualTo("LONG");
        assertThat(p.getQty()).isEqualByComparingTo("0.1");
        assertThat(p.getAvgEntryPrice()).isEqualByComparingTo("42000");
        // initialMargin = 42000 * 0.1 / 10 = 420
        assertThat(p.getFrozenAmount()).isEqualByComparingTo("420");
        // liq(margin-aware) = (42000×0.1 − 420) / (0.1×0.995) = 3780/0.0995 = 37989.94974874
        assertThat(p.getLiquidationPrice()).isEqualByComparingTo("37989.94974874");
        // maint_margin 参考额 = avg × qty × mmr = 42000 × 0.1 × 0.005 = 21
        assertThat(p.getMaintMargin()).isEqualByComparingTo("21");
        // flat→open:openedAt 簿记(资金费 catch-up 下界)
        assertThat(p.getOpenedAt()).isNotNull();
    }

    @Test
    void openLongAddsWithWeightedAvgAndIncrementsFrozen() {
        Position p = longPerp("0.1", "42000", "420", 10);
        BigDecimal pnl = PositionService.applyPerpDelta(p, bd("0.05"), bd("42500"), PositionEffect.OPEN_LONG)
                .realizedPnlDelta();
        assertThat(pnl).isEqualByComparingTo("0");
        assertThat(p.getQty()).isEqualByComparingTo("0.15");
        // (0.1*42000 + 0.05*42500) / 0.15 = (4200 + 2125) / 0.15 = 42166.66666667
        assertThat(p.getAvgEntryPrice()).isEqualByComparingTo("42166.66666667");
        // frozen = 420 + (42500*0.05/10) = 420 + 212.5 = 632.5
        assertThat(p.getFrozenAmount()).isEqualByComparingTo("632.5");
        // liq 重算(margin-aware): (42166.66666667×0.15 − 632.5) / (0.15×0.995)
        //   = (6325.0000000005 − 632.5) / 0.14925 = 5692.5000000005/0.14925 = 38140.70351759
        assertThat(p.getLiquidationPrice()).isEqualByComparingTo("38140.70351759");
        // 加仓不动 openedAt(保留首开时刻)
        assertThat(p.getOpenedAt()).isEqualTo(OPENED_AT);
    }

    // ---------- OPEN_SHORT ----------

    @Test
    void openShortOnFlatSetsShortSideAndFrozen() {
        Position p = flatPerp(20);
        BigDecimal pnl = PositionService.applyPerpDelta(p, bd("0.5"), bd("42000"), PositionEffect.OPEN_SHORT)
                .realizedPnlDelta();
        assertThat(pnl).isEqualByComparingTo("0");
        assertThat(p.getSide()).isEqualTo(Position.SIDE_SHORT);
        assertThat(p.getPositionSide()).isEqualTo("SHORT");
        assertThat(p.getQty()).isEqualByComparingTo("0.5");
        // initialMargin = 42000 * 0.5 / 20 = 1050
        assertThat(p.getFrozenAmount()).isEqualByComparingTo("1050");
        // liq(margin-aware) = (42000×0.5 + 1050) / (0.5×1.005) = 22050/0.5025 = 43880.59701493
        assertThat(p.getLiquidationPrice()).isEqualByComparingTo("43880.59701493");
    }

    @Test
    void openShortAddsWithWeightedAvg() {
        Position p = shortPerp("0.5", "42000", "1050", 20);
        BigDecimal pnl = PositionService.applyPerpDelta(p, bd("0.5"), bd("41000"), PositionEffect.OPEN_SHORT)
                .realizedPnlDelta();
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
        PositionService.PerpFillOutcome out =
                PositionService.applyPerpDelta(p, bd("0.1"), bd("43000"), PositionEffect.CLOSE_LONG);
        BigDecimal pnl = out.realizedPnlDelta();
        // (43000 - 42000) * 0.1 = 100
        assertThat(pnl).isEqualByComparingTo("100");
        // marginDelta = −全平精确释放(余额侧按此解锁 used→free)
        assertThat(out.marginDelta()).isEqualByComparingTo("-420");
        // 全平清零
        assertThat(p.getQty()).isEqualByComparingTo("0");
        assertThat(p.getSide()).isEqualTo(Position.SIDE_FLAT);
        assertThat(p.getAvgEntryPrice()).isNull();
        assertThat(p.getLiquidationPrice()).isNull();
        assertThat(p.getMaintMargin()).isNull();
        assertThat(p.getFrozenAmount()).isEqualByComparingTo("0");
        // positionSide 保留(双向持仓桶身份,V38 唯一索引键不折叠;flat 由 side/qty 表达)
        assertThat(p.getPositionSide()).isEqualTo("LONG");
        // 全平清 openedAt(重开时重新簿记,防 flat 期间历史期次被 catch-up 错误回收)
        assertThat(p.getOpenedAt()).isNull();
        // realizedPnl += 100
        assertThat(p.getRealizedPnl()).isEqualByComparingTo("100");
    }

    @Test
    void closeLongPartialReleaseProportionalFrozenKeepsAvgSide() {
        Position p = longPerp("0.1", "42000", "420", 10);
        PositionService.PerpFillOutcome out =
                PositionService.applyPerpDelta(p, bd("0.04"), bd("43000"), PositionEffect.CLOSE_LONG);
        BigDecimal pnl = out.realizedPnlDelta();
        // (43000 - 42000) * 0.04 = 40
        assertThat(pnl).isEqualByComparingTo("40");
        // marginDelta = −按比例释放 420×0.04/0.1 = −168
        assertThat(out.marginDelta()).isEqualByComparingTo("-168");
        assertThat(p.getQty()).isEqualByComparingTo("0.06");
        // avg 不变
        assertThat(p.getAvgEntryPrice()).isEqualByComparingTo("42000");
        // frozen = 420 - 420*0.04/0.1 = 420 - 168 = 252
        assertThat(p.getFrozenAmount()).isEqualByComparingTo("252");
        // maint_margin 参考额按新 qty 重算 = 42000 × 0.06 × 0.005 = 12.6(不留开仓口径陈旧值)
        assertThat(p.getMaintMargin()).isEqualByComparingTo("12.6");
        // side/positionSide 不变
        assertThat(p.getSide()).isEqualTo(Position.SIDE_LONG);
        assertThat(p.getPositionSide()).isEqualTo("LONG");
        // liquidationPrice 不变(全平才清零):helper 构造时 margin-aware 算的 (4200−420)/0.0995
        assertThat(p.getLiquidationPrice()).isEqualByComparingTo("37989.94974874");
        // 部分平不动 openedAt
        assertThat(p.getOpenedAt()).isEqualTo(OPENED_AT);
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
        BigDecimal pnl = PositionService.applyPerpDelta(p, bd("0.5"), bd("41000"), PositionEffect.CLOSE_SHORT)
                .realizedPnlDelta();
        // (avg - fill) * qty = (42000 - 41000) * 0.5 = 500
        assertThat(pnl).isEqualByComparingTo("500");
        assertThat(p.getQty()).isEqualByComparingTo("0");
        assertThat(p.getSide()).isEqualTo(Position.SIDE_FLAT);
        assertThat(p.getAvgEntryPrice()).isNull();
        assertThat(p.getLiquidationPrice()).isNull();
        assertThat(p.getFrozenAmount()).isEqualByComparingTo("0");
        assertThat(p.getPositionSide()).isEqualTo("SHORT"); // 桶身份保留(同 LONG 全平纪律)
        assertThat(p.getRealizedPnl()).isEqualByComparingTo("500");
    }

    @Test
    void closeShortPartialReleaseProportionalFrozenKeepsAvgSide() {
        Position p = shortPerp("0.5", "42000", "1050", 20);
        BigDecimal pnl = PositionService.applyPerpDelta(p, bd("0.2"), bd("41000"), PositionEffect.CLOSE_SHORT)
                .realizedPnlDelta();
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

    @Test
    void kernelValidationFailureRoutesToRejectFillException() {
        // fillPrice=0 等脏数据被内核校验拒(合法路径上游 guard 已挡死):转 RejectFillException
        // 让订单进 REJECTED 终态自愈,而非落 PaperExecutor 通用重试路径每 tick 重抛;持仓不被修改
        Position p = longPerp("0.1", "42000", "420", 10);
        assertThatThrownBy(() -> PositionService.applyPerpDelta(p, bd("0.05"), bd("0"), PositionEffect.OPEN_LONG))
                .isInstanceOf(RejectFillException.class)
                .hasMessageContaining("PERP fill rejected by math kernel")
                .hasMessageContaining("must be positive");
        assertThat(p.getQty()).isEqualByComparingTo("0.1");
        assertThat(p.getFrozenAmount()).isEqualByComparingTo("420");
    }

    // ---------- 穿蚀仓(frozen<0)旁路:资金费侵蚀穿仓后只放行全平 ----------

    @Test
    void depletedMarginFullCloseBypassesKernelAndZeroesFrozen() {
        // 资金费把 frozen 蚀成 -50(内核 requireNonNegative 会拒):全平旁路——毛 PnL 走 closedPnl,
        // frozen 清零,marginDelta = −frozen = +50(余额侧负释放额走 applyDepletedMarginRelease)
        Position p = longPerp("0.1", "42000", "-50", 10);
        PositionService.PerpFillOutcome out =
                PositionService.applyPerpDelta(p, bd("0.1"), bd("41000"), PositionEffect.CLOSE_LONG);
        // (41000 - 42000) * 0.1 = -100
        assertThat(out.realizedPnlDelta()).isEqualByComparingTo("-100");
        assertThat(out.marginDelta()).isEqualByComparingTo("50");
        assertThat(p.getQty()).isEqualByComparingTo("0");
        assertThat(p.getFrozenAmount()).isEqualByComparingTo("0");
        assertThat(p.getSide()).isEqualTo(Position.SIDE_FLAT);
        assertThat(p.getAvgEntryPrice()).isNull();
        assertThat(p.getLiquidationPrice()).isNull();
        assertThat(p.getPositionSide()).isEqualTo("LONG"); // 桶身份保留
        assertThat(p.getRealizedPnl()).isEqualByComparingTo("-100");
    }

    @Test
    void depletedMarginShortFullCloseBypassesKernel() {
        Position p = shortPerp("0.5", "42000", "-30", 20);
        PositionService.PerpFillOutcome out =
                PositionService.applyPerpDelta(p, bd("0.5"), bd("43000"), PositionEffect.CLOSE_SHORT);
        // (42000 - 43000) * 0.5 = -500
        assertThat(out.realizedPnlDelta()).isEqualByComparingTo("-500");
        assertThat(out.marginDelta()).isEqualByComparingTo("30");
        assertThat(p.getQty()).isEqualByComparingTo("0");
        assertThat(p.getFrozenAmount()).isEqualByComparingTo("0");
        assertThat(p.getPositionSide()).isEqualTo("SHORT");
    }

    @Test
    void depletedMarginPartialCloseRejected() {
        Position p = longPerp("0.1", "42000", "-50", 10);
        assertThatThrownBy(() -> PositionService.applyPerpDelta(p, bd("0.05"), bd("41000"), PositionEffect.CLOSE_LONG))
                .isInstanceOf(RejectFillException.class)
                .hasMessageContaining("margin depleted")
                .hasMessageContaining("only full close");
        // 持仓不被修改(拒单不落账)
        assertThat(p.getQty()).isEqualByComparingTo("0.1");
        assertThat(p.getFrozenAmount()).isEqualByComparingTo("-50");
    }

    @Test
    void depletedMarginAddPositionRejected() {
        Position p = longPerp("0.1", "42000", "-50", 10);
        assertThatThrownBy(() -> PositionService.applyPerpDelta(p, bd("0.05"), bd("41000"), PositionEffect.OPEN_LONG))
                .isInstanceOf(RejectFillException.class)
                .hasMessageContaining("margin depleted");
        assertThat(p.getQty()).isEqualByComparingTo("0.1");
    }

    @Test
    void depletedMarginFullCloseDirtyAvgRoutesToRejectFillException() {
        // frozen<0 且 avgEntryPrice=null(脏行):closedPnl 内核校验拒 → RejectFillException(REJECTED 终态)
        Position p = longPerp("0.1", "42000", "-50", 10);
        p.setAvgEntryPrice(null);
        assertThatThrownBy(() -> PositionService.applyPerpDelta(p, bd("0.1"), bd("41000"), PositionEffect.CLOSE_LONG))
                .isInstanceOf(RejectFillException.class)
                .hasMessageContaining("PERP fill rejected by math kernel");
        assertThat(p.getQty()).isEqualByComparingTo("0.1");
    }
}
