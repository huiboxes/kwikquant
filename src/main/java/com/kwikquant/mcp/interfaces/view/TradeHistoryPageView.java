package com.kwikquant.mcp.interfaces.view;

import static com.kwikquant.mcp.interfaces.view.DecimalStrings.str;

import com.kwikquant.report.application.TradeHistoryService.TradeHistoryItem;
import com.kwikquant.report.application.TradeHistoryService.TradeHistoryStats;
import com.kwikquant.shared.types.PageDto;
import java.time.Instant;
import java.util.List;

/**
 * MCP {@code get_trade_history} 工具返回视图。合并 TradeHistoryService 的分页 items 查询 +
 * 盈亏/手续费统计两次调用，Agent 一次拿到明细+汇总。
 *
 * <p>items/stats 投影为 {@link ItemView}/{@link StatsView}（不直接透传 service record）：金额红线要求
 * MCP 通道金额一律字符串输出，service record 保持 BigDecimal（REST 契约不变），转换收敛在 View 层。
 */
public record TradeHistoryPageView(
        List<ItemView> items, long total, int page, int pageSize, int totalPages, StatsView stats) {
    public static TradeHistoryPageView from(PageDto<TradeHistoryItem> p, TradeHistoryStats stats) {
        return new TradeHistoryPageView(
                p.content().stream().map(ItemView::from).toList(),
                p.total(),
                p.page(),
                p.pageSize(),
                p.totalPages(),
                stats == null ? null : StatsView.from(stats));
    }

    public record ItemView(
            long orderId,
            long accountId,
            String symbol,
            String side,
            String orderType,
            String amount,
            String filledQty,
            String filledAvgPrice,
            String totalFee,
            String totalVolume,
            String status,
            Instant createdAt,
            Instant updatedAt) {
        public static ItemView from(TradeHistoryItem i) {
            return new ItemView(
                    i.orderId(),
                    i.accountId(),
                    i.symbol(),
                    i.side(),
                    i.orderType(),
                    str(i.amount()),
                    str(i.filledQty()),
                    str(i.filledAvgPrice()),
                    str(i.totalFee()),
                    str(i.totalVolume()),
                    i.status(),
                    i.createdAt(),
                    i.updatedAt());
        }
    }

    public record StatsView(
            String totalVolume, String totalFees, String realizedPnl, long tradingDays, String winRate) {
        public static StatsView from(TradeHistoryStats s) {
            return new StatsView(
                    str(s.totalVolume()), str(s.totalFees()), str(s.realizedPnl()), s.tradingDays(), str(s.winRate()));
        }
    }
}
