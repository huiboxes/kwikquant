package com.kwikquant.trading.application;

import com.kwikquant.shared.types.OrderSide;
import com.kwikquant.shared.types.PositionEffect;
import com.kwikquant.trading.domain.Fill;
import com.kwikquant.trading.domain.Order;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 回测虚拟账本(per-taskId 内存)。SPOT 现金/币库存/加权均价/已实现 PnL +
 * PERP per-position 保证金桶/持仓/PnL(最小方案,不模拟强平/资金费率)。
 *
 * <p>SPOT: {@link #apply(Order, Fill)} 按 BUY/SELL(原逻辑)。PERP: 按 {@link PositionEffect} 四向
 * (OPEN_LONG/OPEN_SHORT/CLOSE_LONG/CLOSE_SHORT)。
 *
 * <p>单 Worker 子进程串行逐 bar,无并发锁。生命周期 = task RUNNING 生命周期(initLedger 创建/cleanupLedger 销毁)。
 */
class BacktestLedger {

    private BigDecimal cashBalance;
    private BigDecimal baseInventory = BigDecimal.ZERO;
    private BigDecimal avgEntryPrice = BigDecimal.ZERO;
    private BigDecimal realizedPnl = BigDecimal.ZERO;
    /** PERP per-position 表,key = symbol + ":" + positionSide(LONG/SHORT) */
    private final Map<String, PerpPosition> perpPositions = new LinkedHashMap<>();

    private final AtomicLong nextOrderId = new AtomicLong(1);

    BacktestLedger(BigDecimal initialCapital) {
        this.cashBalance = initialCapital;
    }

    long nextOrderId() {
        return nextOrderId.getAndIncrement();
    }

    /**
     * 撮合后检查账本充足。SPOT BUY:cash≥cost;SPOT SELL:base≥qty;
     * PERP: 回测宽松(保证金桶只记录,不严格拒)——回测是近似,不模拟强平。
     */
    boolean canApply(Order order, Fill fill) {
        if (order.getPositionEffect() != null) {
            return true; // PERP 回测宽松,不模拟强平
        }
        BigDecimal notional = fill.getPrice().multiply(fill.getQty());
        if (fill.getSide() == OrderSide.BUY) {
            return cashBalance.compareTo(notional.add(fill.getFee())) >= 0;
        }
        return baseInventory.compareTo(fill.getQty()) >= 0;
    }

    /**
     * 应用成交。SPOT 按 BUY/SELL(原逻辑);PERP 按 {@link PositionEffect} 四向。
     */
    void apply(Order order, Fill fill) {
        if (order.getPositionEffect() != null) {
            applyPerp(order, fill);
            return;
        }
        applySpot(fill);
    }

    /** SPOT 原逻辑(BUY 扣 cash 加 base + 加权均价;SELL 扣 base 加 cash + 实现 PnL)。 */
    private void applySpot(Fill fill) {
        BigDecimal notional = fill.getPrice().multiply(fill.getQty());
        if (fill.getSide() == OrderSide.BUY) {
            BigDecimal cost = notional.add(fill.getFee());
            BigDecimal newInv = baseInventory.add(fill.getQty());
            avgEntryPrice = baseInventory.signum() == 0
                    ? fill.getPrice()
                    : avgEntryPrice
                            .multiply(baseInventory)
                            .add(fill.getPrice().multiply(fill.getQty()))
                            .divide(newInv, 8, RoundingMode.HALF_UP);
            baseInventory = newInv;
            cashBalance = cashBalance.subtract(cost);
        } else {
            BigDecimal proceeds = notional.subtract(fill.getFee());
            BigDecimal pnl = fill.getPrice()
                    .subtract(avgEntryPrice)
                    .multiply(fill.getQty())
                    .subtract(fill.getFee());
            realizedPnl = realizedPnl.add(pnl);
            baseInventory = baseInventory.subtract(fill.getQty());
            cashBalance = cashBalance.add(proceeds);
        }
    }

    /**
     * PERP 成交(最小方案)。按 {@link PositionEffect} 四向:
     * <ul>
     *   <li>OPEN_LONG/OPEN_SHORT: 加仓 + 冻结 initialMargin(fillPrice×fillQty/leverage,记录用)</li>
     *   <li>CLOSE_LONG/CLOSE_SHORT: 平仓 + realizedPnl + 释放 initialMargin 按比例</li>
     * </ul>
     * 不模拟强平/资金费率。PnL = (fillPrice - avgEntry) × fillQty × sideSign(LONG +1, SHORT -1)。
     */
    private void applyPerp(Order order, Fill fill) {
        String key = order.getSymbol() + ":" + positionSideOf(order.getPositionEffect());
        PerpPosition pos = perpPositions.get(key);
        PositionEffect effect = order.getPositionEffect();
        if (effect == PositionEffect.OPEN_LONG || effect == PositionEffect.OPEN_SHORT) {
            BigDecimal leverage = new BigDecimal(order.getLeverage());
            BigDecimal initialMarginDelta =
                    fill.getPrice().multiply(fill.getQty()).divide(leverage, 8, RoundingMode.HALF_UP);
            if (pos == null) {
                perpPositions.put(key, new PerpPosition(fill.getQty(), fill.getPrice(), initialMarginDelta));
            } else {
                BigDecimal newQty = pos.qty.add(fill.getQty());
                pos.avgEntry = pos.avgEntry
                        .multiply(pos.qty)
                        .add(fill.getPrice().multiply(fill.getQty()))
                        .divide(newQty, 8, RoundingMode.HALF_UP);
                pos.qty = newQty;
                pos.initialMargin = pos.initialMargin.add(initialMarginDelta);
            }
            return;
        }
        // CLOSE_*: 平仓 + PnL + 释放 initialMargin 按比例
        if (pos == null) return; // 无持仓(异常),noop
        BigDecimal sideSign = (effect == PositionEffect.CLOSE_LONG) ? BigDecimal.ONE : BigDecimal.valueOf(-1);
        BigDecimal pnl =
                fill.getPrice().subtract(pos.avgEntry).multiply(fill.getQty()).multiply(sideSign);
        realizedPnl = realizedPnl.add(pnl);
        BigDecimal releaseMargin = pos.qty.signum() > 0
                ? pos.initialMargin.multiply(fill.getQty()).divide(pos.qty, 8, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        pos.initialMargin = pos.initialMargin.subtract(releaseMargin);
        pos.qty = pos.qty.subtract(fill.getQty());
        if (pos.qty.signum() == 0) {
            perpPositions.remove(key); // 全平,清掉
        }
    }

    private static String positionSideOf(PositionEffect effect) {
        return switch (effect) {
            case OPEN_LONG, CLOSE_LONG -> "LONG";
            case OPEN_SHORT, CLOSE_SHORT -> "SHORT";
        };
    }

    public BigDecimal getCashBalance() {
        return cashBalance;
    }

    public BigDecimal getBaseInventory() {
        return baseInventory;
    }

    public BigDecimal getRealizedPnl() {
        return realizedPnl;
    }

    /** PERP 未平仓快照(,供 BacktestResult 组装 unrealizedPnl)。 */
    public Map<String, PerpPosition> getPerpPositions() {
        return perpPositions;
    }

    /** PERP per-position 账本。包私有便测试。 */
    static class PerpPosition {
        BigDecimal qty;
        BigDecimal avgEntry;
        BigDecimal initialMargin;

        PerpPosition(BigDecimal qty, BigDecimal avgEntry, BigDecimal initialMargin) {
            this.qty = qty;
            this.avgEntry = avgEntry;
            this.initialMargin = initialMargin;
        }
    }
}
