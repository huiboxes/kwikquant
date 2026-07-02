package com.kwikquant.strategy.application;

/**
 * Worker 健康检查连续失败到阈值后，由 {@link WorkerOrchestratorService} 发布此事件，
 * {@link StrategyLifecycleService} 监听并调用 {@code markError}。
 *
 * <p>用事件打破 {@code WorkerOrchestratorService ↔ StrategyLifecycleService} 的循环依赖
 * （LifecycleService 依赖 WOS 做 start/stop；WOS 不直接依赖 LifecycleService，改发事件）。
 */
public record WorkerMarkErrorEvent(long strategyId, String reason) {}
