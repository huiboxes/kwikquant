package com.kwikquant.strategy.application;

import java.math.BigDecimal;

/**
 * 回测执行结果。Wave 8 Python Worker 产出，序列化为 JSON 存入 {@code backtest_tasks.result}。
 *
 * <p>Wave 6 为 stub（不产生结果），Wave 8 起由 Worker 适配器填充。字段最小集，Wave 8 可扩展。
 */
public record BacktestResult(BigDecimal realizedPnl, int tradeCount) {}
