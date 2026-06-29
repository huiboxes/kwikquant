package com.kwikquant.market.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.MarketType;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class MarketPropertiesTest {

    @Test
    void bind_whenFullConfig_shouldPopulateFields() {
        var ec = new MarketProperties.ExchangeConfig(
                Exchange.BINANCE, List.of(MarketType.SPOT, MarketType.PERP), List.of("BTC/USDT", "ETH/USDT"));
        var props = new MarketProperties(List.of(ec), Duration.ofSeconds(5), Duration.ofSeconds(30));

        assertThat(props.staleThreshold()).isEqualTo(Duration.ofSeconds(5));
        assertThat(props.idleTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(props.exchanges()).hasSize(1);
        assertThat(props.exchanges().get(0).name()).isEqualTo(Exchange.BINANCE);
    }

    @Test
    void bind_whenMissingStaleThreshold_shouldApplyDefault5s() {
        var props = new MarketProperties(List.of(), null, null);
        assertThat(props.staleThreshold()).isEqualTo(Duration.ofSeconds(5));
        assertThat(props.idleTimeout()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void persistentSymbols_shouldKeyByExchangeAndMarketType() {
        var ec = new MarketProperties.ExchangeConfig(
                Exchange.BINANCE, List.of(MarketType.SPOT, MarketType.PERP), List.of("BTC/USDT", "ETH/USDT"));
        var props = new MarketProperties(List.of(ec), null, null);

        var map = props.persistentSymbols();
        assertThat(map).hasSize(2);
        assertThat(map.get(new MarketProperties.ExchangeMarketKey(Exchange.BINANCE, MarketType.SPOT)))
                .containsExactly("BTC/USDT", "ETH/USDT");
        assertThat(map.get(new MarketProperties.ExchangeMarketKey(Exchange.BINANCE, MarketType.PERP)))
                .containsExactly("BTC/USDT", "ETH/USDT");
    }

    @Test
    void exchangeConfig_whenPersistentSymbolsNull_shouldDefaultEmpty() {
        var ec = new MarketProperties.ExchangeConfig(Exchange.BINANCE, List.of(MarketType.SPOT), null);
        assertThat(ec.persistentSymbols()).isEmpty();
    }
}
