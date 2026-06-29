package com.kwikquant.market.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.Interval;
import com.kwikquant.shared.types.MarketType;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class KlineTest {

    @Test
    void kline_whenValidFields_shouldConstruct() {
        var k = new Kline(
                Exchange.BINANCE,
                MarketType.SPOT,
                "BTC/USDT",
                Interval._1m,
                Instant.parse("2026-06-25T10:00:00Z"),
                BigDecimal.valueOf(50000),
                BigDecimal.valueOf(50100),
                BigDecimal.valueOf(49900),
                BigDecimal.valueOf(50050),
                BigDecimal.valueOf(12.5));
        assertThat(k.exchange()).isEqualTo(Exchange.BINANCE);
        assertThat(k.interval()).isEqualTo(Interval._1m);
        assertThat(k.close()).isEqualByComparingTo("50050");
    }

    @Test
    void kline_whenNullOpenTime_shouldThrowNpe() {
        assertThatThrownBy(() -> new Kline(
                        Exchange.BINANCE,
                        MarketType.SPOT,
                        "BTC/USDT",
                        Interval._1m,
                        null,
                        BigDecimal.valueOf(50000),
                        BigDecimal.valueOf(50100),
                        BigDecimal.valueOf(49900),
                        BigDecimal.valueOf(50050),
                        BigDecimal.valueOf(12.5)))
                .isInstanceOf(NullPointerException.class);
    }
}
