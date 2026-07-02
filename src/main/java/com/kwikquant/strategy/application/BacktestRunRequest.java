package com.kwikquant.strategy.application;

import java.time.Instant;

/**
 * 回测执行请求（Python Worker 适配器消费）。
 *
 * <p>仅定义契约，无实现。由 Python Worker 子进程适配器消费此 request 拉历史 K 线回放。
 */
public record BacktestRunRequest(
        long taskId,
        long strategyId,
        long strategyCodeId,
        long userId,
        String symbol,
        String exchange,
        String intervalValue,
        Instant startTime,
        Instant endTime,
        String parameters) {}
