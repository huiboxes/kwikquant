package com.kwikquant.strategy.application;

import com.kwikquant.strategy.domain.StrategyCode;
import com.kwikquant.strategy.domain.StrategyDefinition;

/**
 * Worker 容器启动配置。安全字段（memoryLimit/cpuLimit）预定义。
 * {@code serviceToken} 由 {@code WorkerTokenService.issueToken} 生成随机 UUID
 * (绑 strategyId+taskType+userId+exchange),通过环境变量 {@code WORKER_SERVICE_TOKEN} 传入容器。
 * Worker 调 {@code POST /api/v1/orders}(实盘/模拟下单)、{@code GET /api/v1/worker/bootstrap}(拉取启动配置)
 * 或 {@code GET /api/v1/backtests/{taskId}/klines}(回测拉数据)时带
 * {@code X-Worker-Token: {serviceToken}} header(与用户 JWT 的
 * {@code Authorization: Bearer} 分道;{@code WorkerTokenFilter} 优先识别 X-Worker-Token)。
 *
 * <p>原 {@code executionTimeoutSec} 字段已删(Wave 3.2c 死代码):runner 为长驻进程无执行超时,
 * 回测超时由 {@code kwikquant.worker.timeout-sec}(BacktestRunner)控制,bootstrap/Python 均不消费此值。
 *
 * @param strategyId 策略 ID
 * @param strategyName 策略名（Docker container name 用）
 * @param sourceCode 策略 Python 源码
 * @param symbol 交易对
 * @param exchange 交易所
 * @param marketType 市场类型（SPOT|PERP；订阅 /topic/kline + 下单 marketType 必填）
 * @param intervalValue K 线周期
 * @param parameters 策略参数 JSON
 * @param apiBaseUrl Java API 端点（Worker 连接用，来源 {@code kwikquant.worker.api-base-url}）
 * @param serviceToken Worker 服务令牌（Java 生成）
 * @param memoryLimitMb 内存上限（默认 512）
 * @param cpuLimit CPU 上限（默认 1）
 */
public record WorkerConfig(
        long strategyId,
        String strategyName,
        String sourceCode,
        String symbol,
        String exchange,
        String marketType,
        String intervalValue,
        String parameters,
        String apiBaseUrl,
        String serviceToken,
        int memoryLimitMb,
        int cpuLimit) {

    private static final int DEFAULT_MEMORY_LIMIT_MB = 512;
    private static final int DEFAULT_CPU_LIMIT = 1;

    public static WorkerConfig forStrategy(
            StrategyDefinition strategy, StrategyCode code, String apiBaseUrl, String serviceToken) {
        return new WorkerConfig(
                strategy.getId(),
                strategy.getName(),
                code.getSourceCode(),
                strategy.getSymbol(),
                strategy.getExchange(),
                strategy.getMarketType(),
                strategy.getIntervalValue(),
                strategy.getParameters(),
                apiBaseUrl,
                serviceToken,
                DEFAULT_MEMORY_LIMIT_MB,
                DEFAULT_CPU_LIMIT);
    }
}
