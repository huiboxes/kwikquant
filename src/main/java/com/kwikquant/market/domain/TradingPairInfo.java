package com.kwikquant.market.domain;

import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.MarketType;
import java.math.BigDecimal;

public record TradingPairInfo(
        Exchange exchange,
        MarketType marketType,
        String symbol,
        String baseAsset,
        String quoteAsset,
        BigDecimal minQty,
        BigDecimal maxQty,
        BigDecimal tickSize,
        BigDecimal stepSize,
        boolean active) {}
