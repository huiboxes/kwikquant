package com.kwikquant.strategy.application;

import com.kwikquant.strategy.domain.StrategyCode;
import com.kwikquant.strategy.domain.StrategyDefinition;

/**
 * Worker 容器启动配置。安全字段（memoryLimit/cpuLimit/executionTimeout）预定义（spec-review S-6），
 * Wave 8 启用。{@code serviceToken} 由 Java 生成随机 UUID，通过环境变量 {@code WORKER_SERVICE_TOKEN} 传入容器，
 * Worker 调 {@code POST /api/v1/orders} 时带 {@code Authorization: Bearer {serviceToken}}（trading SecurityConfig 放行）。
 *
 * @param strategyId 策略 ID
 * @param strategyName 策略名（Docker container name 用）
 * @param sourceCode 策略 Python 源码
 * @param symbol 交易对
 * @param exchange 交易所
 * @param intervalValue K 线周期
 * @param parameters 策略参数 JSON
 * @param apiBaseUrl Java API 端点（Worker 连接用，来源 {@code kwikquant.worker.api-base-url}）
 * @param serviceToken Worker 服务令牌（Java 生成）
 * @param memoryLimitMb 内存上限（默认 512）
 * @param cpuLimit CPU 上限（默认 1）
 * @param executionTimeoutSec 执行超时（默认 3600）
 */
public record WorkerConfig(
        long strategyId,
        String strategyName,
        String sourceCode,
        String symbol,
        String exchange,
        String intervalValue,
        String parameters,
        String apiBaseUrl,
        String serviceToken,
        int memoryLimitMb,
        int cpuLimit,
        int executionTimeoutSec) {

    public static WorkerConfig forStrategy(
            StrategyDefinition strategy, StrategyCode code, String apiBaseUrl, String serviceToken) {
        return new WorkerConfig(
                strategy.getId(),
                strategy.getName(),
                code.getSourceCode(),
                strategy.getSymbol(),
                strategy.getExchange(),
                strategy.getIntervalValue(),
                strategy.getParameters(),
                apiBaseUrl,
                serviceToken,
                512,
                1,
                3600);
    }
}
