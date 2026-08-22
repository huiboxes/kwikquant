package com.kwikquant.mcp.interfaces.view;

import static com.kwikquant.mcp.interfaces.view.DecimalStrings.str;

import com.kwikquant.market.domain.FundingRate;
import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.MarketType;
import java.time.Instant;

/**
 * MCP {@code get_funding_rate} 工具返回的资金费率投影。剥掉 domain 的 {@code receivedAt}。
 * 金额红线：费率/标记价一律字符串输出。
 */
public record FundingRateView(
        Exchange exchange,
        MarketType marketType,
        String symbol,
        String fundingRate,
        String markPrice,
        String nextFundingRate,
        Instant nextFundingTime,
        Instant timestamp) {
    public static FundingRateView from(FundingRate fr) {
        return new FundingRateView(
                fr.exchange(),
                fr.marketType(),
                fr.symbol(),
                str(fr.fundingRate()),
                str(fr.markPrice()),
                str(fr.nextFundingRate()),
                fr.nextFundingTime(),
                fr.timestamp());
    }
}
