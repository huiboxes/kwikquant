package com.kwikquant.market.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kwikquant.shared.infra.ProxyProperties;
import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.MarketType;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CcxtExchangeRegistryTest {

    private static MarketProperties props() {
        var binance = new MarketProperties.ExchangeConfig(
                Exchange.BINANCE, List.of(MarketType.SPOT, MarketType.PERP), List.of("BTC/USDT", "ETH/USDT"));
        return new MarketProperties(List.of(binance), Duration.ofSeconds(5), Duration.ofSeconds(30));
    }

    /** 无代理(直连),供不需要 proxy 的测试用。 */
    private static ProxyProperties noProxy() {
        return new ProxyProperties(null, Map.of());
    }

    @Test
    void getExchange_whenConfigured_shouldReturnInstance() {
        var registry = new CcxtExchangeRegistry(props(), noProxy());
        registry.init();

        assertThat(registry.getExchange(Exchange.BINANCE, MarketType.SPOT)).isNotNull();
        assertThat(registry.getExchange(Exchange.BINANCE, MarketType.PERP)).isNotNull();
    }

    @Test
    void getExchange_whenNotConfigured_shouldThrowIllegalArgument() {
        var registry = new CcxtExchangeRegistry(props(), noProxy());
        registry.init();

        assertThatThrownBy(() -> registry.getExchange(Exchange.OKX, MarketType.SPOT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exchange not configured")
                .hasMessageContaining("OKX_SPOT");
    }

    @Test
    void init_shouldCreateAllConfiguredExchanges() {
        var registry = new CcxtExchangeRegistry(props(), noProxy());
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
                new MarketProperties(List.of(paper), Duration.ofSeconds(5), Duration.ofSeconds(30)), noProxy());
        assertThatThrownBy(registry::init)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PAPER");
    }

    @Test
    void getExchange_whenOkxSpotConfigured_shouldReturnInstance() {
        var okx =
                new MarketProperties.ExchangeConfig(Exchange.OKX, List.of(MarketType.SPOT, MarketType.PERP), List.of());
        var registry = new CcxtExchangeRegistry(
                new MarketProperties(List.of(okx), Duration.ofSeconds(5), Duration.ofSeconds(30)), noProxy());
        registry.init();
        assertThat(registry.getExchange(Exchange.OKX, MarketType.SPOT)).isNotNull();
        assertThat(registry.getExchange(Exchange.OKX, MarketType.PERP)).isNotNull();
    }

    @Test
    void getExchange_whenBitgetPerpConfigured_shouldReturnInstance() {
        var bitget = new MarketProperties.ExchangeConfig(Exchange.BITGET, List.of(MarketType.PERP), List.of());
        var registry = new CcxtExchangeRegistry(
                new MarketProperties(List.of(bitget), Duration.ofSeconds(5), Duration.ofSeconds(30)), noProxy());
        registry.init();
        assertThat(registry.getExchange(Exchange.BITGET, MarketType.PERP)).isNotNull();
    }

    @Test
    void createExchange_whenProxyConfigured_appliesToExchange() {
        var proxy = new ProxyProperties(
                new ProxyProperties.ProxyConfig("http://127.0.0.1:13659", "socks5://127.0.0.1:13659", false), Map.of());
        var binance =
                new MarketProperties.ExchangeConfig(Exchange.BINANCE, List.of(MarketType.SPOT), List.of("BTC/USDT"));
        var registry = new CcxtExchangeRegistry(
                new MarketProperties(List.of(binance), Duration.ofSeconds(5), Duration.ofSeconds(30)), proxy);
        registry.init();
        var ex = registry.getExchange(Exchange.BINANCE, MarketType.SPOT);
        assertThat(ex.wsSocksProxy).isEqualTo("socks5://127.0.0.1:13659");
    }

    @Test
    void createExchange_whenOverrideDirect_forcesDirect() {
        // 全局有代理,但 BINANCE override direct=true → 该所直连(wsSocksProxy null)
        var proxy = new ProxyProperties(
                new ProxyProperties.ProxyConfig("http://g:13659", "socks5://g:13659", false),
                Map.of(Exchange.BINANCE, new ProxyProperties.ProxyConfig(null, null, true)));
        var binance = new MarketProperties.ExchangeConfig(Exchange.BINANCE, List.of(MarketType.SPOT), List.of());
        var registry = new CcxtExchangeRegistry(
                new MarketProperties(List.of(binance), Duration.ofSeconds(5), Duration.ofSeconds(30)), proxy);
        registry.init();
        var ex = registry.getExchange(Exchange.BINANCE, MarketType.SPOT);
        assertThat(ex.wsSocksProxy).isNull();
    }
}
