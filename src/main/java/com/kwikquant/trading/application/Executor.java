package com.kwikquant.trading.application;

import com.kwikquant.trading.domain.Order;

/**
 * Executor SPI: BacktestExecutor / PaperExecutor / LiveExecutor 共同接口。
 *
 * <p>{@link #submit(Order)} 接已 INSERT 的 Order（status=NEW），异步推进状态。
 *
 * <p>{@link #cancel(Order)} 接当前 SUBMITTED/PARTIALLY_FILLED 的 Order，推进 PENDING_CANCEL → CANCELLED。
 */
public interface Executor {

    void submit(Order order);

    void cancel(Order order);

    /**
     * 清除某账户在 Executor 内存活跃订单池(重置模拟盘账户用)。PaperExecutor override 实际清池;
     * Live/BacktestExecutor 无内存池,default no-op。重置时调,避免已 DB CANCELLED 的订单
     * 仍留内存池被 onTicker 撮合。
     */
    default void clearActiveOrdersByAccount(long accountId) {
        // no-op: Live/Backtest 无内存活跃订单池
    }
}
