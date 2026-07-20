package com.kwikquant.market.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kwikquant.market.domain.SymbolNotListedException;
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

    // ── canonical → CCXT unified symbol 反向索引(纯函数 indexByCanonical + resolveOrThrow)──

    /** spot markets:canonical BTC/USDT → unified BTC/USDT(同形)。 */
    @Test
    void indexByCanonical_whenSpotMarket_returnsIdentity() {
        Object markets = Map.of(
                "BTC/USDT", Map.of("symbol", "BTC/USDT", "base", "BTC", "quote", "USDT", "type", "spot"),
                "ETH/USDT", Map.of("symbol", "ETH/USDT", "base", "ETH", "quote", "USDT", "type", "spot"));
        Map<String, String> indexed = CcxtExchangeRegistry.indexByCanonical(markets);
        assertThat(indexed).hasSize(2);
        assertThat(CcxtExchangeRegistry.resolveOrThrow(indexed, Exchange.BINANCE, MarketType.SPOT, "BTC/USDT"))
                .isEqualTo("BTC/USDT");
    }

    /**
     * PERP/swap markets:unified symbol 带 :USDT 后缀({@code BTC/USDT:USDT}),但 base/quote 仍是 BTC/USDT,
     * 故 canonical {@code BTC/USDT} 应反查到 {@code BTC/USDT:USDT}。这正是 bug 根因的修复验证。
     */
    @Test
    void indexByCanonical_whenPerpMarket_returnsSuffixedUnified() {
        Object markets = Map.of(
                "BTC/USDT:USDT",
                Map.of("symbol", "BTC/USDT:USDT", "base", "BTC", "quote", "USDT", "settle", "USDT", "type", "swap"),
                "ETH/USDT:USDT",
                Map.of("symbol", "ETH/USDT:USDT", "base", "ETH", "quote", "USDT", "settle", "USDT", "type", "swap"));
        Map<String, String> indexed = CcxtExchangeRegistry.indexByCanonical(markets);
        assertThat(CcxtExchangeRegistry.resolveOrThrow(indexed, Exchange.OKX, MarketType.PERP, "BTC/USDT"))
                .isEqualTo("BTC/USDT:USDT");
        assertThat(CcxtExchangeRegistry.resolveOrThrow(indexed, Exchange.OKX, MarketType.PERP, "ETH/USDT"))
                .isEqualTo("ETH/USDT:USDT");
    }

    /**
     * 线性 vs 反向合约去歧义:反向合约 {@code BTC/USD:BTC} 的 quote=USD(不是 USDT),canonical
     * {@code BTC/USD} 与线性 {@code BTC/USDT} 不撞——base/quote 天然区分,索引无碰撞。
     */
    @Test
    void indexByCanonical_whenMixOfLinearAndInverse_noCanonicalCollision() {
        Object markets = Map.of(
                "BTC/USDT:USDT",
                Map.of("symbol", "BTC/USDT:USDT", "base", "BTC", "quote", "USDT", "type", "swap"),
                "BTC/USD:BTC",
                Map.of("symbol", "BTC/USD:BTC", "base", "BTC", "quote", "USD", "type", "swap"));
        Map<String, String> indexed = CcxtExchangeRegistry.indexByCanonical(markets);
        assertThat(indexed).hasSize(2);
        assertThat(indexed.get("BTC/USDT")).isEqualTo("BTC/USDT:USDT");
        assertThat(indexed.get("BTC/USD")).isEqualTo("BTC/USD:BTC");
    }

    /** markets 缺 base/quote/symbol 字段 → 跳过;全部缺失 → 空表(后续 resolveOrThrow 抛 not-listed)。 */
    @Test
    void indexByCanonical_whenMarketMissingFields_skipsEntry() {
        Object markets = Map.of(
                "BAD", Map.of("symbol", "BAD"), // 缺 base/quote
                "BTC/USDT", Map.of("symbol", "BTC/USDT", "base", "BTC", "quote", "USDT"));
        Map<String, String> indexed = CcxtExchangeRegistry.indexByCanonical(markets);
        assertThat(indexed).hasSize(1).containsKey("BTC/USDT");
    }

    /** markets 非 Map(如 CCXT 未加载返回 null)→ 空表,不抛(调用方随后 resolveOrThrow 抛 not-listed)。 */
    @Test
    void indexByCanonical_whenNotMap_returnsEmpty() {
        assertThat(CcxtExchangeRegistry.indexByCanonical(null)).isEmpty();
        assertThat(CcxtExchangeRegistry.indexByCanonical("not a map")).isEmpty();
    }

    /** canonical 命不到 → SymbolNotListedException(携带 exchange/marketType/canonical,便于定位)。 */
    @Test
    void resolveOrThrow_whenCanonicalNotFound_throwsSymbolNotListed() {
        Map<String, String> indexed = Map.of("BTC/USDT", "BTC/USDT:USDT");
        assertThatThrownBy(
                        () -> CcxtExchangeRegistry.resolveOrThrow(indexed, Exchange.OKX, MarketType.PERP, "DOGE/USDT"))
                .isInstanceOf(SymbolNotListedException.class)
                .hasMessageContaining("DOGE/USDT")
                .hasMessageContaining("OKX")
                .hasMessageContaining("PERP");
    }

    /**
     * 入口集成测试:用真实 Binance 实例(经 createExchange 构造,proxy direct),注入 markets +
     * {@code marketsLoaded=true} 跳过 loadMarkets 联网,验证 {@code ccxtSymbol} 端到端翻译 + 缓存。
     * 这是修复 PERP topic bug 的顶层断言:canonical {@code BTC/USDT} 在 PERP 实例上 → {@code BTC/USDT:USDT}。
     */
    @Test
    void ccxtSymbol_whenPerpMarketLoaded_returnsSuffixedAndCaches() {
        var binance = new MarketProperties.ExchangeConfig(
                Exchange.BINANCE, List.of(MarketType.SPOT, MarketType.PERP), List.of("BTC/USDT"));
        var registry = new CcxtExchangeRegistry(
                new MarketProperties(List.of(binance), Duration.ofSeconds(5), Duration.ofSeconds(30)), noProxy());
        registry.init();

        // 注入 PERP 实例的 markets(模拟 loadMarkets 结果)+ 标记已加载,绕过联网
        var perpEx = registry.getExchange(Exchange.BINANCE, MarketType.PERP);
        perpEx.markets = Map.of(
                "BTC/USDT:USDT",
                Map.of("symbol", "BTC/USDT:USDT", "base", "BTC", "quote", "USDT", "type", "swap"),
                "ETH/USDT:USDT",
                Map.of("symbol", "ETH/USDT:USDT", "base", "ETH", "quote", "USDT", "type", "swap"));
        perpEx.marketsLoaded = true;

        // SPOT 实例未注入(走 identity 语义也应能工作,但这里聚焦 PERP 翻译)
        assertThat(registry.ccxtSymbol(Exchange.BINANCE, MarketType.PERP, "BTC/USDT"))
                .isEqualTo("BTC/USDT:USDT");
        assertThat(registry.ccxtSymbol(Exchange.BINANCE, MarketType.PERP, "ETH/USDT"))
                .isEqualTo("ETH/USDT:USDT");

        // 命不到 → SymbolNotListedException
        assertThatThrownBy(() -> registry.ccxtSymbol(Exchange.BINANCE, MarketType.PERP, "DOGE/USDT"))
                .isInstanceOf(SymbolNotListedException.class);
    }
}
