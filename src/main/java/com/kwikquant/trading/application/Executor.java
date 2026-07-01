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
}
