package com.kwikquant.trading.application;

import com.kwikquant.shared.types.OrderSide;
import com.kwikquant.trading.domain.Fill;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 回测虚拟账本(per-taskId 内存,§3.1)。现金/币库存/加权均价/已实现 PnL。
 *
 * <p>逻辑抽自 Wave 4 死代码 {@code BacktestExecutor.runInternal}(cash check + 加仓均价 + 平仓 PnL),per-order 调用。
 * 单 Worker 子进程串行逐 bar,无并发锁。生命周期 = task RUNNING 生命周期(initLedger 创建/cleanupLedger 销毁)。
 */
class BacktestLedger {

    private BigDecimal cashBalance;
    private BigDecimal baseInventory = BigDecimal.ZERO;
    private BigDecimal avgEntryPrice = BigDecimal.ZERO;
    private BigDecimal realizedPnl = BigDecimal.ZERO;
    private final AtomicLong nextOrderId = new AtomicLong(1);

    BacktestLedger(BigDecimal initialCapital) {
        this.cashBalance = initialCapital;
    }

    long nextOrderId() {
        return nextOrderId.getAndIncrement();
    }

    /** 撮合后检查账本充足(BUY:cash≥cost;SELL:base≥qty)。 */
    boolean canApply(Fill fill) {
        BigDecimal notional = fill.getPrice().multiply(fill.getQty());
        if (fill.getSide() == OrderSide.BUY) {
            return cashBalance.compareTo(notional.add(fill.getFee())) >= 0;
        }
        return baseInventory.compareTo(fill.getQty()) >= 0;
    }

    /** 应用成交(BUY:扣cash加base+加权均价;SELL:扣base加cash+实现PnL)。调用前需 canApply 校验。 */
    void apply(Fill fill) {
        BigDecimal notional = fill.getPrice().multiply(fill.getQty());
        if (fill.getSide() == OrderSide.BUY) {
            BigDecimal cost = notional.add(fill.getFee());
            BigDecimal newInv = baseInventory.add(fill.getQty());
            avgEntryPrice = baseInventory.signum() == 0
                    ? fill.getPrice()
                    : avgEntryPrice.multiply(baseInventory)
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

    public BigDecimal getCashBalance() {
        return cashBalance;
    }

    public BigDecimal getBaseInventory() {
        return baseInventory;
    }

    public BigDecimal getRealizedPnl() {
        return realizedPnl;
    }
}
