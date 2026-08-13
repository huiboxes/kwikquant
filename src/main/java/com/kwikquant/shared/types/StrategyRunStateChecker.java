package com.kwikquant.shared.types;

/** 跨模块只读端口：worker 下单前确认策略仍处于 RUNNING。 */
public interface StrategyRunStateChecker {

    boolean isRunning(long strategyId);
}
