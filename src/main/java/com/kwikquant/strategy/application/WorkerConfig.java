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
 * <p>原 {@code executionTimeoutSec} 死字段已删:runner 为长驻进程无执行超时,
 * 回测超时由 {@code kwikquant.worker.timeout-sec}(BacktestRunner)控制,bootstrap/Python 均不消费此值。
 *
 * @param strategyId 策略 ID
 * @param userId 策略所属用户 ID（runner 订阅 user 级 WS topic `/topic/fills|liquidations|funding/{userId}`
 *        派发策略事件回调用，经 {@code WorkerBootstrapView} 下发；docs/ws-contract.md §5）
 * @param accountId 绑定的交易所账户 ID（fills/liquidations/funding topic 是 user 级、覆盖该用户
 *        **全部账户**；runner 事件回调按 accountId 过滤只派发本账户事件，防同一用户 PAPER/LIVE
 *        多账户互相泄漏进策略回调——触碰模拟盘/实盘强区分红线；同经 {@code WorkerBootstrapView} 下发）
 * @param strategyName 策略名（Docker container name 用）
 * @param sourceCode 策略 Python 源码
 * @param symbol 交易对
 * @param exchange 交易所
 * @param marketType 市场类型（SPOT|PERP；订阅 /topic/kline + 下单 marketType 必填）
 * @param leverage 策略级默认杠杆（V44 绑定，PERP 订单未显式传 leverage 时的缺省值；SPOT null）
 * @param marginMode 策略级默认保证金模式（ISOLATED|CROSS，同 leverage 缺省语义；SPOT null）
 * @param intervalValue K 线周期
 * @param parameters 策略参数 JSON
 * @param apiBaseUrl Java API 端点（Worker 连接用，来源 {@code kwikquant.worker.api-base-url}）
 * @param serviceToken Worker 服务令牌（Java 生成）
 * @param incarnation 容器世代 UUID（Java 每次启动生成,经 env {@code WORKER_INCARNATION} 注入,
 *        worker /health 原样回传,WOS 据此把健康快照归属到当前容器——容器名跨重启复用）
 * @param memoryLimitMb 内存上限（默认 512）
 * @param cpuLimit CPU 上限（默认 1）
 */
public record WorkerConfig(
        long strategyId,
        long userId,
        Long accountId,
        String strategyName,
        String sourceCode,
        String symbol,
        String exchange,
        String marketType,
        Integer leverage,
        String marginMode,
        String intervalValue,
        String parameters,
        String apiBaseUrl,
        String serviceToken,
        String incarnation,
        int memoryLimitMb,
        int cpuLimit) {

    private static final int DEFAULT_MEMORY_LIMIT_MB = 512;
    private static final int DEFAULT_CPU_LIMIT = 1;

    public static WorkerConfig forStrategy(
            StrategyDefinition strategy,
            StrategyCode code,
            String apiBaseUrl,
            String serviceToken,
            String incarnation) {
        return new WorkerConfig(
                strategy.getId(),
                strategy.getUserId(),
                strategy.getExchangeAccountId(),
                strategy.getName(),
                code.getSourceCode(),
                strategy.getSymbol(),
                strategy.getExchange(),
                strategy.getMarketType(),
                strategy.getLeverage(),
                strategy.getMarginMode(),
                strategy.getIntervalValue(),
                strategy.getParameters(),
                apiBaseUrl,
                serviceToken,
                incarnation,
                DEFAULT_MEMORY_LIMIT_MB,
                DEFAULT_CPU_LIMIT);
    }
}
