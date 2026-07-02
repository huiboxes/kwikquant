package com.kwikquant.strategy.application;

/**
 * 回测执行 SPI（Wave 8 扩展点）。
 *
 * <p>Wave 6 无实现（{@code BacktestExecutionGateway} 注入 {@code Optional<BacktestRunner>}，为空时走 stub）。
 * Wave 8 由 Python Worker 子进程适配器实现：拉历史 K 线 → 逐 bar {@code on_bar} → 策略下单意图 →
 * {@code POST /api/v1/orders} → Java 撮合（{@code BacktestMatcher}）→ 汇总结果。strategy 模块不依赖 trading。
 *
 * @see BacktestRunRequest
 * @see BacktestResult
 */
public interface BacktestRunner {

    BacktestResult run(BacktestRunRequest request);
}
