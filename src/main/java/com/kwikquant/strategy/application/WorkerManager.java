package com.kwikquant.strategy.application;

/**
 * Worker 容器管理 SPI。Wave 6 由 {@code DockerWorkerManager}（infrastructure）实现，
 * 通过 {@code ProcessBuilder} 执行 {@code docker run/stop/rm/inspect}（不引入 docker-java 库）。
 */
public interface WorkerManager {

    /** 创建并启动容器，返回 containerId。失败抛 {@code WorkerStartFailedException}。 */
    String createAndStart(WorkerConfig config);

    void stop(String containerId);

    boolean isRunning(String containerId);

    /** HTTP GET {@code /health}，5s 超时。 */
    boolean healthCheck(String containerId);

    void remove(String containerId);
}
