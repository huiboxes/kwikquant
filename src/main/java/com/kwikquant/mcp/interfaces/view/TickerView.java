package com.kwikquant.mcp.interfaces.view;

import static com.kwikquant.mcp.interfaces.view.DecimalStrings.str;

import com.kwikquant.market.domain.Ticker;
import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.MarketType;
import java.time.Instant;

/**
 * MCP {@code get_ticker} 工具返回的 ticker 投影。剥掉 domain {@link Ticker} 的 {@code receivedAt}
 * （本端缓存时刻，对 Agent 无意义），暴露行情字段 + 原始 timestamp。金额红线：价格/量/涨跌幅一律字符串输出。
 */
public record TickerView(
        Exchange exchange,
        MarketType marketType,
        String symbol,
        String last,
        String bid,
        String ask,
        String high,
        String low,
        String open,
        String baseVolume,
        String quoteVolume,
        String change,
        String percentage,
        Instant timestamp) {
    public static TickerView from(Ticker t) {
        return new TickerView(
                t.exchange(),
                t.marketType(),
                t.symbol(),
                str(t.last()),
                str(t.bid()),
                str(t.ask()),
                str(t.high()),
                str(t.low()),
                str(t.open()),
                str(t.baseVolume()),
                str(t.quoteVolume()),
                str(t.change()),
                str(t.percentage()),
                t.timestamp());
    }
}
