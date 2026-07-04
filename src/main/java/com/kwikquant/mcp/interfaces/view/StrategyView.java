package com.kwikquant.mcp.interfaces.view;

import com.kwikquant.shared.types.StrategyStatus;
import com.kwikquant.strategy.domain.StrategyDefinition;

/**
 * MCP 策略启停工具（{@code start_paper_trading} / {@code start_live_trading}）返回的 StrategyView。
 * 剥掉 description/parameters/deleted 等内部字段，暴露 id/name/exchange/intervalValue/status。
 */
public record StrategyView(long id, String name, String exchange, String intervalValue, StrategyStatus status) {
    public static StrategyView from(StrategyDefinition s) {
        return new StrategyView(s.getId(), s.getName(), s.getExchange(), s.getIntervalValue(), s.getStatus());
    }
}
