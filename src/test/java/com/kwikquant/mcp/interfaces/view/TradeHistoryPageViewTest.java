package com.kwikquant.mcp.interfaces.view;

import static org.assertj.core.api.Assertions.assertThat;

import com.kwikquant.report.application.TradeHistoryService.TradeHistoryItem;
import com.kwikquant.report.application.TradeHistoryService.TradeHistoryStats;
import com.kwikquant.shared.types.PageDto;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * TradeHistoryPageView 投影单测:items/stats 由 service record(BigDecimal)转字符串视图
 * (金额红线,MCP 通道金额一律字符串输出);null stats 透传 null 而非空对象。
 */
class TradeHistoryPageViewTest {

    @Test
    void from_itemsAndStats_decimalFieldsStringified() {
        TradeHistoryItem item = new TradeHistoryItem(
                10L,
                1L,
                "BTC/USDT",
                "BUY",
                "MARKET",
                new BigDecimal("0.1"),
                new BigDecimal("0.1"),
                new BigDecimal("50000.5"),
                new BigDecimal("0.001"),
                new BigDecimal("5000.25"),
                "FILLED",
                Instant.parse("2024-01-01T00:00:00Z"),
                Instant.parse("2024-01-01T00:00:05Z"));
        TradeHistoryStats stats = new TradeHistoryStats(
                new BigDecimal("5000.25"),
                new BigDecimal("0.001"),
                new BigDecimal("-12.5"),
                5,
                new BigDecimal("0.6000"));
        PageDto<TradeHistoryItem> page = new PageDto<>(List.of(item), 1, 20, 1L, 1);

        TradeHistoryPageView view = TradeHistoryPageView.from(page, stats);

        TradeHistoryPageView.ItemView iv = view.items().get(0);
        assertThat(iv.orderId()).isEqualTo(10L);
        assertThat(iv.symbol()).isEqualTo("BTC/USDT");
        assertThat(iv.amount()).isEqualTo("0.1");
        assertThat(iv.filledQty()).isEqualTo("0.1");
        assertThat(iv.filledAvgPrice()).isEqualTo("50000.5");
        assertThat(iv.totalFee()).isEqualTo("0.001");
        assertThat(iv.totalVolume()).isEqualTo("5000.25");
        assertThat(iv.status()).isEqualTo("FILLED");

        TradeHistoryPageView.StatsView sv = view.stats();
        assertThat(sv.totalVolume()).isEqualTo("5000.25");
        assertThat(sv.totalFees()).isEqualTo("0.001");
        assertThat(sv.realizedPnl()).isEqualTo("-12.5");
        assertThat(sv.tradingDays()).isEqualTo(5);
        assertThat(sv.winRate()).isEqualTo("0.6000");
    }

    @Test
    void from_nullStats_returnsNullStatsView() {
        PageDto<TradeHistoryItem> page = new PageDto<>(List.of(), 1, 20, 0L, 0);

        TradeHistoryPageView view = TradeHistoryPageView.from(page, null);

        assertThat(view.items()).isEmpty();
        assertThat(view.stats()).isNull();
    }

    @Test
    void from_nullDecimalFields_passThroughNull() {
        TradeHistoryItem item = new TradeHistoryItem(
                11L,
                1L,
                "BTC/USDT",
                "BUY",
                "MARKET",
                null,
                null,
                null,
                null,
                null,
                "FILLED",
                Instant.parse("2024-01-01T00:00:00Z"),
                Instant.parse("2024-01-01T00:00:05Z"));
        PageDto<TradeHistoryItem> page = new PageDto<>(List.of(item), 1, 20, 1L, 1);

        TradeHistoryPageView.ItemView iv =
                TradeHistoryPageView.from(page, null).items().get(0);

        assertThat(iv.amount()).isNull();
        assertThat(iv.filledAvgPrice()).isNull();
        assertThat(iv.totalFee()).isNull();
    }
}
