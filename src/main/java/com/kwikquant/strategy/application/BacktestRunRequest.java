package com.kwikquant.strategy.application;

import java.time.Instant;

/**
 * 回测执行请求(Wave 8 PythonSubprocessBacktestRunner 消费)。Wave 8 加 {@code serviceToken}(plan-外:
 * Gateway issueToken 后传入,Runner 放 env WORKER_SERVICE_TOKEN)。
 *
 * @param taskId 回测任务 ID
 * @param strategyId 策略 ID
 * @param strategyCodeId 代码版本 ID
 * @param userId 用户 ID
 * @param symbol 交易对
 * @param exchange 交易所
 * @param intervalValue K 线周期
 * @param startTime 回测开始
 * @param endTime 回测结束
 * @param parameters 策略参数 JSON(含 initial_capital)
 * @param serviceToken Worker 服务令牌(Gateway issueToken,Worker 调 Java REST 用)
 * @param marketType 市场类型(从策略派生,Worker 调 /klines 用;不存 backtest_tasks 表)
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
        String parameters,
        String serviceToken,
        String marketType) {}
