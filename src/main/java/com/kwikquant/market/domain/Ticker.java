package com.kwikquant.market.domain;

import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.MarketType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

public record Ticker(
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
        Instant timestamp,
        Instant receivedAt) {

    public Ticker {
        Objects.requireNonNull(exchange);
        Objects.requireNonNull(marketType);
        Objects.requireNonNull(symbol);
        Objects.requireNonNull(timestamp);
        Objects.requireNonNull(receivedAt);
    }
}
