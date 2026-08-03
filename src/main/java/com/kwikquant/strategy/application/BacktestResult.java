package com.kwikquant.strategy.application;

import java.math.BigDecimal;

/**
 * 回测执行结果。Worker stdout 输出的回测结果 JSON 解析产出。{@code section8Json} 携带结果 JSON 原文
 * (避免改 BacktestRunner SPI 返回类型),Gateway 据此调 ReportService.submitBacktestResult。
 *
 * @param realizedPnl 已实现 PnL(从 equity_curve 末-首推,粗略,精确 metrics 在 backtest_reports)
 * @param tradeCount 成交笔数(trades.size())
 * @param section8Json Worker stdout 回测结果原文 JSON(传 ReportService 解析为 trades+equity+metrics)
 */
public record BacktestResult(BigDecimal realizedPnl, int tradeCount, String section8Json) {}
