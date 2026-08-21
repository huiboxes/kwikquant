package com.kwikquant.strategy.application;

import java.time.Instant;
import java.util.Objects;

/**
 * Worker 运行时状态（内存 Registry，不持久化）。应用重启丢失→由 reconcile 重建。
 *
 * @param strategyId 策略 ID
 * @param containerId Docker 容器名（{@code strategy-worker-{id}}，跨重启复用，不是容器实例身份）
 * @param incarnation 容器世代 UUID（每次 createAndStart 新生成；健康快照归属比对用，
 *        容器名复用导致名字无法区分新旧实例——见 {@link WorkerOrchestratorService#healthCheckAll}）
 * @param running 是否运行中
 * @param lastHealthCheck 上次健康检查时间
 * @param consecutiveFailures 连续健康检查失败次数（健康时重置为 0）
 */
public record WorkerStatus(
        long strategyId,
        String containerId,
        String incarnation,
        boolean running,
        Instant lastHealthCheck,
        int consecutiveFailures) {

    public WorkerStatus onHealthy(Instant now) {
        return new WorkerStatus(strategyId, containerId, incarnation, true, now, 0);
    }

    public WorkerStatus onUnhealthy(Instant now) {
        return new WorkerStatus(strategyId, containerId, incarnation, false, now, consecutiveFailures + 1);
    }

    public WorkerStatus withContainer(String newContainerId, String newIncarnation, Instant now) {
        return new WorkerStatus(strategyId, newContainerId, newIncarnation, true, now, consecutiveFailures);
    }

    /** 世代匹配:incarnation 相同才是同一容器实例。null 兼容旧路径(双 null 视为匹配,退回名字语义)。 */
    public boolean sameIncarnation(WorkerStatus other) {
        return other != null && Objects.equals(incarnation, other.incarnation);
    }
}
