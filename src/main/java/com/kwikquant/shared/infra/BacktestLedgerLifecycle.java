package com.kwikquant.shared.infra;

import java.math.BigDecimal;

/**
 * 回测虚拟账本生命周期 SPI(Wave 8 §3.6)。
 *
 * <p>归属 shared::infra(与 {@link WorkerTokenService} 同理):
 * strategy({@code BacktestExecutionGateway})调用 initLedger/cleanupLedger,
 * trading({@code BacktestOrderService})提供实现;两侧都依赖 shared,不产生
 * strategy→trading 的 Java 内部依赖(§1.1 硬约束)。
 *
 * <p>生命周期语义:回测任务 CAS PENDING→RUNNING 后调 {@link #initLedger},
 * 无论成功/失败 finally 块调 {@link #cleanupLedger}(防内存残留,C4/R6)。
 */
public interface BacktestLedgerLifecycle {

    /** 初始化 per-taskId 虚拟账本(内存)。initialCapital 为回测起始资金。 */
    void initLedger(long taskId, BigDecimal initialCapital);

    /** 清理 per-taskId 虚拟账本(内存)。幂等,已清理再调无副作用。 */
    void cleanupLedger(long taskId);
}
