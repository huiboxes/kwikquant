package com.kwikquant.strategy.application;

import java.util.List;

/**
 * Worker 容器管理 SPI。由 {@code DockerWorkerManager}（infrastructure）实现,
 * 通过 {@link SubprocessExecutor} 执行 {@code docker run/stop/rm/inspect/ps}(不引入 docker-java 库)。
 */
public interface WorkerManager {

    /** Worker 容器命名前缀(协议常量,DockerWorkerManager 创建 + WOS 编排共用,单一真相源)。 */
    String CONTAINER_NAME_PREFIX = "strategy-worker-";

    /** 创建并启动容器,返回 containerId。失败抛 {@code WorkerStartFailedException}。 */
    String createAndStart(WorkerConfig config);

    void stop(String containerId);

    /**
     * 探活结果:{@code healthy} 判定 + {@code incarnation}(worker /health 回传的容器世代 UUID,
     * 旧镜像无此字段时为 {@code null})。容器名 {@code strategy-worker-{id}} 跨重启复用,registry 条目
     * 归属必须比对 incarnation(见 {@link WorkerOrchestratorService#healthCheckAll}),不能只信名字。
     */
    record HealthCheckResult(boolean healthy, String incarnation) {}

    /** 查容器是否健康(运行中)。实现可代理 docker inspect 或 HTTP /health;不可达 → healthy=false。 */
    HealthCheckResult healthCheck(String containerId);

    void remove(String containerId);

    /**
     * 列出所有 {@code strategy-worker-*} 容器名({@code docker ps -a --filter name=}),供 WOS 孤儿 GC 对账。
     * 返回所有(含在用 + exited 残留);WOS 据 registry 区分。daemon 异常致 {@code --rm} 未清的残留在此捕获。
     */
    List<String> listStrategyWorkerContainers();
}
