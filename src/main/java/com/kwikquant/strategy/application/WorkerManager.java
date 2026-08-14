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

    /** 查容器是否健康(运行中)。实现可代理 docker inspect 或 HTTP /health。 */
    boolean healthCheck(String containerId);

    void remove(String containerId);

    /**
     * 列出所有 {@code strategy-worker-*} 容器名({@code docker ps -a --filter name=}),供 WOS 孤儿 GC 对账。
     * 返回所有(含在用 + exited 残留);WOS 据 registry 区分。daemon 异常致 {@code --rm} 未清的残留在此捕获。
     */
    List<String> listStrategyWorkerContainers();
}
