package com.kwikquant.shared.types;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

public record TickEvent(
        Exchange exchange,
        Symbol symbol,
        BigDecimal lastPrice,
        BigDecimal markPrice,
        BigDecimal volume24h,
        Instant eventTime,
        Instant receivedAt,
        Long sourceSequence,
        MarketDataQualityStatus qualityStatus) {

    public TickEvent {
        Objects.requireNonNull(exchange);
        Objects.requireNonNull(symbol);
        Objects.requireNonNull(eventTime);
        Objects.requireNonNull(receivedAt);
        Objects.requireNonNull(qualityStatus);
    }

    public TickEvent withQualityStatus(MarketDataQualityStatus newStatus) {
        return new TickEvent(
                exchange, symbol, lastPrice, markPrice, volume24h, eventTime, receivedAt, sourceSequence, newStatus);
    }
}
