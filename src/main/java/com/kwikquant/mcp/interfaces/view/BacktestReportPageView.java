package com.kwikquant.mcp.interfaces.view;

import static com.kwikquant.mcp.interfaces.view.DecimalStrings.str;

import com.kwikquant.report.domain.BacktestReport;
import com.kwikquant.shared.types.PageDto;
import java.time.Instant;
import java.util.List;

/**
 * MCP {@code list_backtests} 工具返回的分页视图。{@link BacktestReportView} 嵌套暴露 BacktestReport 的
 * 绩效字段（totalReturn/sharpe/maxDrawdown/winRate/profitFactor/totalTrades），剥掉 params/equityCurve
 * 原始 JSON（Agent 可按需调 get_trade_history 或后续 report 详情工具，YAGNI 当前不暴露）。
 * 金额红线：收益率/回撤等指标一律字符串输出（{@link ComparisonView} 同用此投影）。
 */
public record BacktestReportPageView(
        List<BacktestReportView> items, long total, int page, int pageSize, int totalPages) {
    public static BacktestReportPageView from(PageDto<BacktestReport> p) {
        return new BacktestReportPageView(
                p.content().stream().map(BacktestReportView::from).toList(),
                p.total(),
                p.page(),
                p.pageSize(),
                p.totalPages());
    }

    public record BacktestReportView(
            long id,
            String name,
            String symbol,
            String timeframe,
            Instant periodStart,
            Instant periodEnd,
            String totalReturn,
            String sharpeRatio,
            String maxDrawdown,
            String winRate,
            String profitFactor,
            int totalTrades) {
        public static BacktestReportView from(BacktestReport r) {
            return new BacktestReportView(
                    r.getId(),
                    r.getName(),
                    r.getSymbol(),
                    r.getTimeframe(),
                    r.getPeriodStart(),
                    r.getPeriodEnd(),
                    str(r.getTotalReturn()),
                    str(r.getSharpeRatio()),
                    str(r.getMaxDrawdown()),
                    str(r.getWinRate()),
                    str(r.getProfitFactor()),
                    r.getTotalTrades());
        }
    }
}
