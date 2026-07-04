package com.kwikquant.mcp.interfaces.view;

import com.kwikquant.market.domain.Kline;
import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.Interval;
import com.kwikquant.shared.types.MarketType;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * MCP {@code get_ohlcv} 工具返回的 K 线投影。剥掉 domain {@link Kline} 无需对 Agent 暴露的内部字段
 * （Kline 无敏感字段，投影保持字段对齐，仅作为模块边界隔离层，未来 domain 变更不直接冲击 MCP 契约）。
 */
public record KlineView(
        Exchange exchange,
        MarketType marketType,
        String symbol,
        Interval interval,
        Instant openTime,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        BigDecimal volume) {
    public static KlineView from(Kline k) {
        return new KlineView(
                k.exchange(),
                k.marketType(),
                k.symbol(),
                k.interval(),
                k.openTime(),
                k.open(),
                k.high(),
                k.low(),
                k.close(),
                k.volume());
    }
}
