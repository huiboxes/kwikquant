package com.kwikquant.strategy.application;

import java.time.Instant;

/**
 * Worker 运行时状态（内存 Registry，不持久化）。应用重启丢失→由 reconcile 重建。
 *
 * @param strategyId 策略 ID
 * @param containerId Docker 容器 ID
 * @param running 是否运行中
 * @param lastHealthCheck 上次健康检查时间
 * @param consecutiveFailures 连续健康检查失败次数（健康时重置为 0）
 */
public record WorkerStatus(
        long strategyId, String containerId, boolean running, Instant lastHealthCheck, int consecutiveFailures) {

    public WorkerStatus onHealthy(Instant now) {
        return new WorkerStatus(strategyId, containerId, true, now, 0);
    }

    public WorkerStatus onUnhealthy(Instant now) {
        return new WorkerStatus(strategyId, containerId, false, now, consecutiveFailures + 1);
    }

    public WorkerStatus withContainer(String newContainerId, Instant now) {
        return new WorkerStatus(strategyId, newContainerId, true, now, consecutiveFailures);
    }
}
