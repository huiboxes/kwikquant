package com.kwikquant.market.domain;

import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.Interval;
import com.kwikquant.shared.types.MarketType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

public record Kline(
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

    public Kline {
        Objects.requireNonNull(exchange);
        Objects.requireNonNull(marketType);
        Objects.requireNonNull(symbol);
        Objects.requireNonNull(interval);
        Objects.requireNonNull(openTime);
    }
}
