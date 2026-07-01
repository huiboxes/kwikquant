package com.kwikquant.trading.domain;

import static org.assertj.core.api.Assertions.*;

import com.kwikquant.market.domain.Kline;
import com.kwikquant.market.domain.Ticker;
import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.Interval;
import com.kwikquant.shared.types.MarketType;
import com.kwikquant.shared.types.PriceLevel;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class MarketSnapshotTest {

    @Test
    void fromKline_populatesOhlcvAndLastFromClose() {
        Kline k = new Kline(
                Exchange.BINANCE,
                MarketType.SPOT,
                "BTC/USDT",
                Interval._1m,
                Instant.parse("2026-06-30T00:00:00Z"),
                new BigDecimal("42000"),
                new BigDecimal("42100"),
                new BigDecimal("41950"),
                new BigDecimal("42050"),
                new BigDecimal("12.5"));
        MarketSnapshot s = MarketSnapshot.fromKline(k);
        assertThat(s.open()).isEqualByComparingTo("42000");
        assertThat(s.high()).isEqualByComparingTo("42100");
        assertThat(s.low()).isEqualByComparingTo("41950");
        assertThat(s.close()).isEqualByComparingTo("42050");
        assertThat(s.last()).isEqualByComparingTo("42050");
        assertThat(s.volume()).isEqualByComparingTo("12.5");
        assertThat(s.bid()).isNull();
        assertThat(s.ask()).isNull();
        assertThat(s.bids()).isEmpty();
        assertThat(s.asks()).isEmpty();
    }

    @Test
    void fromTicker_populatesLastBidAsk() {
        Instant t = Instant.parse("2026-06-30T00:00:00Z");
        Ticker tk = new Ticker(
                Exchange.BINANCE,
                MarketType.SPOT,
                "BTC/USDT",
                new BigDecimal("42000"),
                new BigDecimal("41995"),
                new BigDecimal("42005"),
                null,
                null,
                null,
                new BigDecimal("100"),
                null,
                null,
                null,
                t,
                t);
        MarketSnapshot s = MarketSnapshot.fromTicker(tk);
        assertThat(s.last()).isEqualByComparingTo("42000");
        assertThat(s.bid()).isEqualByComparingTo("41995");
        assertThat(s.ask()).isEqualByComparingTo("42005");
        assertThat(s.volume()).isEqualByComparingTo("100");
        assertThat(s.bids()).isEmpty();
    }

    @Test
    void fromOrderBook_carriesBidsAsks() {
        Instant t = Instant.parse("2026-06-30T00:00:00Z");
        Ticker tk = new Ticker(
                Exchange.BINANCE,
                MarketType.SPOT,
                "BTC/USDT",
                new BigDecimal("42000"),
                new BigDecimal("41995"),
                new BigDecimal("42005"),
                null,
                null,
                null,
                BigDecimal.ONE,
                null,
                null,
                null,
                t,
                t);
        List<PriceLevel> bids = List.of(new PriceLevel(new BigDecimal("41995"), new BigDecimal("1.0")));
        List<PriceLevel> asks = List.of(new PriceLevel(new BigDecimal("42005"), new BigDecimal("0.5")));
        MarketSnapshot s = MarketSnapshot.fromOrderBook(tk, bids, asks);
        assertThat(s.bids()).hasSize(1);
        assertThat(s.asks()).hasSize(1);
        assertThat(s.bids().get(0).price()).isEqualByComparingTo("41995");
    }

    @Test
    void nullBidsAsksDefaultToEmpty() {
        MarketSnapshot s = new MarketSnapshot(
                Instant.now(),
                BigDecimal.ONE,
                null,
                null,
                BigDecimal.ONE,
                BigDecimal.ONE,
                BigDecimal.ONE,
                BigDecimal.ONE,
                null,
                null,
                null);
        assertThat(s.bids()).isEmpty();
        assertThat(s.asks()).isEmpty();
    }
}
