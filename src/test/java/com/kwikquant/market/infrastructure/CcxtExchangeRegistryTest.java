package com.kwikquant.market.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.MarketType;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class CcxtExchangeRegistryTest {

    private static MarketProperties props() {
        var binance = new MarketProperties.ExchangeConfig(
                Exchange.BINANCE, List.of(MarketType.SPOT, MarketType.PERP), List.of("BTC/USDT", "ETH/USDT"));
        return new MarketProperties(List.of(binance), Duration.ofSeconds(5), Duration.ofSeconds(30));
    }

    @Test
    void getExchange_whenConfigured_shouldReturnInstance() {
        var registry = new CcxtExchangeRegistry(props());
        registry.init();

        assertThat(registry.getExchange(Exchange.BINANCE, MarketType.SPOT)).isNotNull();
        assertThat(registry.getExchange(Exchange.BINANCE, MarketType.PERP)).isNotNull();
    }

    @Test
    void getExchange_whenNotConfigured_shouldThrowIllegalArgument() {
        var registry = new CcxtExchangeRegistry(props());
        registry.init();

        assertThatThrownBy(() -> registry.getExchange(Exchange.OKX, MarketType.SPOT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exchange not configured")
                .hasMessageContaining("OKX_SPOT");
    }

    @Test
    void init_shouldCreateAllConfiguredExchanges() {
        var registry = new CcxtExchangeRegistry(props());
        registry.init();
        // BINANCE × {SPOT, PERP} = 2 实例
        assertThat(registry.getExchange(Exchange.BINANCE, MarketType.SPOT))
                .isSameAs(registry.getExchange(Exchange.BINANCE, MarketType.SPOT));
        assertThat(registry.getExchange(Exchange.BINANCE, MarketType.PERP))
                .isNotSameAs(registry.getExchange(Exchange.BINANCE, MarketType.SPOT));
    }

    @Test
    void createExchange_forPaper_shouldThrow() {
        var paper = new MarketProperties.ExchangeConfig(Exchange.PAPER, List.of(MarketType.SPOT), List.of());
        var registry = new CcxtExchangeRegistry(
                new MarketProperties(List.of(paper), Duration.ofSeconds(5), Duration.ofSeconds(30)));
        assertThatThrownBy(registry::init)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PAPER");
    }

    @Test
    void getExchange_whenOkxSpotConfigured_shouldReturnInstance() {
        var okx =
                new MarketProperties.ExchangeConfig(Exchange.OKX, List.of(MarketType.SPOT, MarketType.PERP), List.of());
        var registry = new CcxtExchangeRegistry(
                new MarketProperties(List.of(okx), Duration.ofSeconds(5), Duration.ofSeconds(30)));
        registry.init();
        assertThat(registry.getExchange(Exchange.OKX, MarketType.SPOT)).isNotNull();
        assertThat(registry.getExchange(Exchange.OKX, MarketType.PERP)).isNotNull();
    }

    @Test
    void getExchange_whenBitgetPerpConfigured_shouldReturnInstance() {
        var bitget = new MarketProperties.ExchangeConfig(Exchange.BITGET, List.of(MarketType.PERP), List.of());
        var registry = new CcxtExchangeRegistry(
                new MarketProperties(List.of(bitget), Duration.ofSeconds(5), Duration.ofSeconds(30)));
        registry.init();
        assertThat(registry.getExchange(Exchange.BITGET, MarketType.PERP)).isNotNull();
    }
}
