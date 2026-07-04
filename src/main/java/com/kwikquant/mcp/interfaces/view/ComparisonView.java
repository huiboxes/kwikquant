package com.kwikquant.mcp.interfaces.view;

import com.kwikquant.report.application.ComparisonResult;
import java.util.List;
import java.util.Map;

/**
 * MCP {@code compare_backtests} 工具返回的对比视图。{@code reports} 为各 BacktestReport 投影，
 * {@code ranking} 为指标→按该指标排序的 reportId 列表（透传 {@link ComparisonResult#ranking()}）。
 */
public record ComparisonView(List<BacktestReportPageView.BacktestReportView> reports, Map<String, List<Long>> ranking) {
    public static ComparisonView from(ComparisonResult c) {
        return new ComparisonView(
                c.reports().stream()
                        .map(BacktestReportPageView.BacktestReportView::from)
                        .toList(),
                c.ranking());
    }
}
