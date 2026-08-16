package com.kwikquant.mcp.interfaces.view;

/** start_live_trading 两阶段确认预览:实盘启动要素回显(真实资金,不可逆)。 */
public record StartLiveTradingPreview(
        Long strategyId,
        String strategyName,
        Long accountId,
        String accountName,
        String exchange,
        String symbol,
        String intervalValue) {}
