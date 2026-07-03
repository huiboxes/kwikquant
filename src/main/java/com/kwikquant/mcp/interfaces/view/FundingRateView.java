package com.kwikquant.mcp.interfaces.view;

import com.kwikquant.market.domain.FundingRate;
import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.MarketType;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * MCP {@code get_funding_rate} 工具返回的资金费率投影。剥掉 domain 的 {@code receivedAt}。
 */
public record FundingRateView(
        Exchange exchange,
        MarketType marketType,
        String symbol,
        BigDecimal fundingRate,
        BigDecimal markPrice,
        BigDecimal nextFundingRate,
        Instant nextFundingTime,
        Instant timestamp) {
    public static FundingRateView from(FundingRate fr) {
        return new FundingRateView(
                fr.exchange(),
                fr.marketType(),
                fr.symbol(),
                fr.fundingRate(),
                fr.markPrice(),
                fr.nextFundingRate(),
                fr.nextFundingTime(),
                fr.timestamp());
    }
}
