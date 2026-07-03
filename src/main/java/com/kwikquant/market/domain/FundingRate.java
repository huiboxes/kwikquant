package com.kwikquant.market.domain;

import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.MarketType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * 资金费率快照（仅永续合约）。CCXT {@code fetchFundingRate} 返回的当前/下一轮费率及标记价。
 * 除 exchange/marketType/symbol/receivedAt 外字段均可空（交易所实现差异），由调用方按需 null 检查。
 */
public record FundingRate(
        Exchange exchange,
        MarketType marketType,
        String symbol,
        BigDecimal fundingRate,
        BigDecimal markPrice,
        BigDecimal nextFundingRate,
        Instant nextFundingTime,
        Instant timestamp,
        Instant receivedAt) {

    public FundingRate {
        Objects.requireNonNull(exchange);
        Objects.requireNonNull(marketType);
        Objects.requireNonNull(symbol);
        Objects.requireNonNull(receivedAt);
    }
}
