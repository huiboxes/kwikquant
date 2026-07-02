package com.kwikquant.strategy.application;

import java.math.BigDecimal;

/**
 * 回测执行结果。Worker stdout §8 JSON 解析产出。Wave 8 扩 {@code section8Json}(plan-外:避 SPI 改返回类型,
 * 把 §8 原文随 BacktestResult 带回,Gateway 据此调 ReportService.submitBacktestResult)。
 *
 * @param realizedPnl 已实现 PnL(从 equity_curve 末-首推,粗略,精确 metrics 在 backtest_reports)
 * @param tradeCount 成交笔数(trades.size())
 * @param section8Json Worker stdout §8 原文 JSON(传 ReportService 解析为 trades+equity+metrics)
 */
public record BacktestResult(BigDecimal realizedPnl, int tradeCount, String section8Json) {

    /** Wave 6 stub 兼容(无 §8)。 */
    public static BacktestResult stub() {
        return new BacktestResult(BigDecimal.ZERO, 0, null);
    }
}
