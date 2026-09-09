package com.kwikquant.mcp.interfaces.view;

import com.kwikquant.report.application.ComparisonResult;
import java.util.List;
import java.util.Map;

/**
 * MCP {@code compare_backtests} 工具返回的对比视图。{@code reports} 为各 BacktestReport 投影
 * （含 marketType），{@code ranking} 为指标→按该指标排序的 reportId 列表（透传
 * {@link ComparisonResult#ranking()}）。
 *
 * <p>{@code mixedMarketTypes}：SPOT 与 PERP 报告混排时为 true——两者指标口径不可比
 * （PERP winRate/profitFactor 是毛配对不含资金费，totalReturn 走含资金费的权益曲线，
 * docs/perp-backtest-spec.md §8.2），Agent 不得据混排 ranking 直接推荐"最优"，更不得直通
 * start_live_trading。
 */
public record ComparisonView(
        List<BacktestReportPageView.BacktestReportView> reports,
        Map<String, List<Long>> ranking,
        boolean mixedMarketTypes) {
    public static ComparisonView from(ComparisonResult c) {
        List<BacktestReportPageView.BacktestReportView> reports = c.reports().stream()
                .map(BacktestReportPageView.BacktestReportView::from)
                .toList();
        // null(存量报告,market_type 列晚于其产生)归一 SPOT:PERP 报告晚于该列出现,
        // null 只可能是 SPOT,不归一会把存量 SPOT 对比误报成混排
        boolean mixed = reports.stream()
                        .map(r -> r.marketType() == null ? "SPOT" : r.marketType())
                        .distinct()
                        .count()
                > 1;
        return new ComparisonView(reports, c.ranking(), mixed);
    }
}
