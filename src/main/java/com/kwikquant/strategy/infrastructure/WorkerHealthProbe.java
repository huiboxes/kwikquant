package com.kwikquant.strategy.infrastructure;

import java.util.Optional;

/**
 * Worker 健康探活 SPI:HTTP GET worker 容器 {@code /health} 端点取存活信号快照。
 *
 * <p>与 {@link com.kwikquant.strategy.application.SubprocessExecutor} 同模式——daemon/网络依赖的 SPI,
 * 实现类({@link RealWorkerHealthProbe})的 HTTP 发送部分经 JaCoCo 排除(真实 worker-net 探活留 CI),
 * 逻辑经 mock 本 SPI 全分支单测覆盖。
 *
 * <p>归 infrastructure(探活方式是 {@link DockerWorkerManager} 的实现细节,非 application 关心)。
 * {@link DockerWorkerManager#healthCheck} 委托本 SPI 取快照 + {@code isWorkerHealthy} 纯函数判定。
 */
interface WorkerHealthProbe {

    /**
     * 探活指定 worker 容器,返回 {@code /health} 快照。
     *
     * @param containerId 容器名({@code strategy-worker-{strategyId}})
     * @return 快照(HTTP 200 + 合法 JSON);网络不可达/非 200/反序列化失败返 {@link Optional#empty()}
     */
    Optional<WorkerHealthSnapshot> probe(String containerId);
}
