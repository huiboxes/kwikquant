package com.kwikquant.market.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.MarketType;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class TickerTest {

    private static Ticker ticker(Exchange ex, String symbol) {
        return new Ticker(
                ex,
                MarketType.SPOT,
                symbol,
                BigDecimal.valueOf(50000),
                BigDecimal.valueOf(49999),
                BigDecimal.valueOf(50001),
                BigDecimal.valueOf(51000),
                BigDecimal.valueOf(49000),
                BigDecimal.valueOf(49500),
                BigDecimal.valueOf(100),
                BigDecimal.valueOf(5_000_000),
                BigDecimal.valueOf(500),
                BigDecimal.valueOf(1.01),
                Instant.parse("2026-06-25T10:00:00Z"),
                Instant.parse("2026-06-25T10:00:01Z"));
    }

    @Test
    void ticker_whenValidFields_shouldConstruct() {
        var t = ticker(Exchange.BINANCE, "BTC/USDT");
        assertThat(t.exchange()).isEqualTo(Exchange.BINANCE);
        assertThat(t.symbol()).isEqualTo("BTC/USDT");
        assertThat(t.last()).isEqualByComparingTo("50000");
    }

    @Test
    void ticker_whenNullExchange_shouldThrowNpe() {
        assertThatThrownBy(() -> ticker(null, "BTC/USDT")).isInstanceOf(NullPointerException.class);
    }

    @Test
    void ticker_whenNullSymbol_shouldThrowNpe() {
        assertThatThrownBy(() -> ticker(Exchange.BINANCE, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void marketDataException_shouldCarryRetryableFlag() {
        var ex = new MarketDataException("boom", true);
        assertThat(ex.isRetryable()).isTrue();
        assertThat(ex.getMessage()).isEqualTo("boom");
    }

    @Test
    void marketDataException_withCause_shouldCarryRetryableAndCause() {
        var cause = new RuntimeException("root");
        var ex = new MarketDataException("wrapped", cause, false);
        assertThat(ex.isRetryable()).isFalse();
        assertThat(ex.getMessage()).isEqualTo("wrapped");
        assertThat(ex.getCause()).isSameAs(cause);
    }
}
