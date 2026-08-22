package com.kwikquant.strategy.application;

/**
 * worker 环境自检落定事件：自检（含自动搭建）跑到明确结论时由
 * {@link BacktestWorkerHealthChecker} 发布。{@link BacktestTaskRecovery} 用它把
 * "环境未就绪期间积压的 PENDING 回测"推迟到自检结束后再入队，避免任务在搭建窗口
 * 内白跑一次直接失败。
 */
public record WorkerEnvironmentSettledEvent(boolean available) {}
