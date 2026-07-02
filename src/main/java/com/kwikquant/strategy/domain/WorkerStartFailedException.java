package com.kwikquant.strategy.domain;

/**
 * Worker 容器启动失败异常。
 *
 * <p>映射到 {@code ErrorCode.WORKER_START_FAILED}(7200)。{@code StrategyLifecycleService.start} 捕获后
 * 策略状态保持不变（不转 RUNNING）。
 */
public class WorkerStartFailedException extends RuntimeException {

    private final long strategyId;

    public WorkerStartFailedException(long strategyId, String reason, Throwable cause) {
        super("Worker start failed for strategy " + strategyId + ": " + reason, cause);
        this.strategyId = strategyId;
    }

    public long strategyId() {
        return strategyId;
    }
}
