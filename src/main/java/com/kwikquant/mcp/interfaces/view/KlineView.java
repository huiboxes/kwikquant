package com.kwikquant.mcp.interfaces.view;

import static com.kwikquant.mcp.interfaces.view.DecimalStrings.str;

import com.kwikquant.market.domain.Kline;
import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.Interval;
import com.kwikquant.shared.types.MarketType;
import java.time.Instant;

/**
 * MCP {@code get_ohlcv} 工具返回的 K 线投影。剥掉 domain {@link Kline} 无需对 Agent 暴露的内部字段
 * （Kline 无敏感字段，投影保持字段对齐，仅作为模块边界隔离层，未来 domain 变更不直接冲击 MCP 契约）。
 * 金额红线：OHLC 价格与成交量一律字符串输出。
 */
public record KlineView(
        Exchange exchange,
        MarketType marketType,
        String symbol,
        Interval interval,
        Instant openTime,
        String open,
        String high,
        String low,
        String close,
        String volume) {
    public static KlineView from(Kline k) {
        return new KlineView(
                k.exchange(),
                k.marketType(),
                k.symbol(),
                k.interval(),
                k.openTime(),
                str(k.open()),
                str(k.high()),
                str(k.low()),
                str(k.close()),
                str(k.volume()));
    }
}
