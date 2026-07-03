package com.kwikquant.mcp.interfaces.view;

import com.kwikquant.market.domain.Ticker;
import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.MarketType;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * MCP {@code get_ticker} 工具返回的 ticker 投影。剥掉 domain {@link Ticker} 的 {@code receivedAt}
 * （本端缓存时刻，对 Agent 无意义），暴露行情字段 + 原始 timestamp。
 */
public record TickerView(
        Exchange exchange,
        MarketType marketType,
        String symbol,
        BigDecimal last,
        BigDecimal bid,
        BigDecimal ask,
        BigDecimal high,
        BigDecimal low,
        BigDecimal open,
        BigDecimal baseVolume,
        BigDecimal quoteVolume,
        BigDecimal change,
        BigDecimal percentage,
        Instant timestamp) {
    public static TickerView from(Ticker t) {
        return new TickerView(
                t.exchange(),
                t.marketType(),
                t.symbol(),
                t.last(),
                t.bid(),
                t.ask(),
                t.high(),
                t.low(),
                t.open(),
                t.baseVolume(),
                t.quoteVolume(),
                t.change(),
                t.percentage(),
                t.timestamp());
    }
}
