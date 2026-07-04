package com.kwikquant.mcp.interfaces.view;

import com.kwikquant.report.application.TradeHistoryService.TradeHistoryItem;
import com.kwikquant.report.application.TradeHistoryService.TradeHistoryStats;
import com.kwikquant.shared.types.PageDto;
import java.util.List;

/**
 * MCP {@code get_trade_history} 工具返回视图。合并 {@link TradeHistoryService#query}（分页 items）+
 * {@link TradeHistoryService#stats}（盈亏/手续费统计）两次调用，Agent 一次拿到明细+汇总。
 * items/stats 透传 service record（无敏感字段）。
 */
public record TradeHistoryPageView(
        List<TradeHistoryItem> items, long total, int page, int pageSize, int totalPages, TradeHistoryStats stats) {
    public static TradeHistoryPageView from(PageDto<TradeHistoryItem> p, TradeHistoryStats stats) {
        return new TradeHistoryPageView(p.content(), p.total(), p.page(), p.pageSize(), p.totalPages(), stats);
    }
}
