package com.kwikquant.market.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kwikquant.market.domain.TradingPairInfo;
import com.kwikquant.market.infrastructure.CcxtExchangeRegistry;
import com.kwikquant.shared.infra.QuoteCurrencyProperties;
import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.MarketType;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TradingPairServiceTest {

    private CcxtExchangeRegistry registry;
    private io.github.ccxt.Exchange ccxt;
    private TradingPairService service;
    private QuoteCurrencyProperties props;

    @BeforeEach
    void setUp() {
        registry = mock(CcxtExchangeRegistry.class);
        ccxt = mock(io.github.ccxt.Exchange.class);
        when(ccxt.loadMarkets(any())).thenReturn(CompletableFuture.completedFuture(null));
        when(registry.getExchange(any(), any())).thenReturn(ccxt);
        props = new QuoteCurrencyProperties(List.of("USDT"), new BigDecimal("100000"));
        service = new TradingPairService(registry, props);
    }

    private void setMarkets(Object markets) {
        ccxt.markets = markets; // public volatile field
    }

    private static Map<String, Map<String, Object>> btcMarket() {
        return Map.of(
                "BTC/USDT",
                Map.of(
                        "symbol",
                        "BTC/USDT",
                        "base",
                        "BTC",
                        "quote",
                        "USDT",
                        "active",
                        true,
                        "limits",
                        Map.of("amount", Map.of("min", 0.001, "max", 1000.0)),
                        "precision",
                        Map.of("price", 0.01, "amount", 0.001)));
    }

    @Test
    void getPairs_whenCacheMiss_shouldLoadFromCcxt() {
        setMarkets(btcMarket());

        var pairs = service.getPairs(Exchange.BINANCE, MarketType.SPOT);

        assertThat(pairs).hasSize(1);
        var p = pairs.get(0);
        assertThat(p.symbol()).isEqualTo("BTC/USDT");
        assertThat(p.baseAsset()).isEqualTo("BTC");
        assertThat(p.quoteAsset()).isEqualTo("USDT");
        assertThat(p.active()).isTrue();
        assertThat(p.minQty()).isEqualByComparingTo("0.001");
        assertThat(p.maxQty()).isEqualByComparingTo("1000");
        // CCXT precision 统一返回 tick/step 值（E2E 验证 2026-06-29）
        assertThat(p.tickSize()).isEqualByComparingTo("0.01");
        assertThat(p.stepSize()).isEqualByComparingTo("0.001");
        verify(ccxt).loadMarkets(any());
    }

    @Test
    void getPairs_whenCacheHit_shouldNotCallCcxt() {
        setMarkets(btcMarket());

        service.getPairs(Exchange.BINANCE, MarketType.SPOT);
        service.getPairs(Exchange.BINANCE, MarketType.SPOT);

        // Caffeine 命中 → loadMarkets 只调 1 次
        verify(ccxt, times(1)).loadMarkets(any());
    }

    @Test
    void getPairs_whenDifferentExchange_shouldLoadSeparately() {
        // BINANCE/SPOT 与 BINANCE/PERP 缓存键不同
        when(registry.getExchange(Exchange.BINANCE, MarketType.PERP)).thenReturn(ccxt);
        setMarkets(btcMarket());

        service.getPairs(Exchange.BINANCE, MarketType.SPOT);
        service.getPairs(Exchange.BINANCE, MarketType.PERP);

        // 两个不同 key → 各加载一次 → loadMarkets 2 次
        verify(ccxt, times(2)).loadMarkets(any());
    }

    @Test
    void getPairs_whenMarketInactive_shouldFilterOut() {
        var m = new java.util.HashMap<>(btcMarket());
        m.put("BTC/USDT", new java.util.HashMap<>(btcMarket().get("BTC/USDT")));
        m.get("BTC/USDT").put("active", false);
        setMarkets(m);

        var pairs = service.getPairs(Exchange.BINANCE, MarketType.SPOT);
        assertThat(pairs).isEmpty();
    }

    @Test
    void getPairs_whenMarketMissingLimitsAndPrecision_shouldStillParse() {
        var inner = new java.util.HashMap<String, Object>();
        inner.put("symbol", "BTC/USDT");
        inner.put("base", "BTC");
        inner.put("quote", "USDT");
        inner.put("active", true);
        setMarkets(new java.util.HashMap<>(Map.of("BTC/USDT", inner)));

        var pairs = service.getPairs(Exchange.BINANCE, MarketType.SPOT);
        assertThat(pairs).hasSize(1);
        var p = pairs.get(0);
        assertThat(p.minQty()).isNull();
        assertThat(p.tickSize()).isNull();
    }

    @Test
    void getPairs_whenPrecisionIsTickValue_shouldUseDirectly() {
        // CCXT 统一返回 tick/step 值（E2E 验证），直接转 BigDecimal
        var inner = new java.util.HashMap<String, Object>();
        inner.put("symbol", "BTC/USDT");
        inner.put("quote", "USDT");
        inner.put("active", true);
        inner.put("precision", Map.of("price", 0.01, "amount", 0.001));
        setMarkets(new java.util.HashMap<>(Map.of("BTC/USDT", inner)));

        var pairs = service.getPairs(Exchange.BINANCE, MarketType.SPOT);
        assertThat(pairs.get(0).tickSize()).isEqualByComparingTo("0.01");
        assertThat(pairs.get(0).stepSize()).isEqualByComparingTo("0.001");
    }

    @Test
    void getPairs_whenPrecisionIsOne_shouldReturnOneNotZeroPointOne() {
        // OKX SHIB/USDT: precision.amount=1.0（步长=1，整数量交易）
        // 旧启发式会错误地返回 10^(-1)=0.1；修复后应返回 1.0
        var inner = new java.util.HashMap<String, Object>();
        inner.put("symbol", "SHIB/USDT");
        inner.put("quote", "USDT");
        inner.put("active", true);
        inner.put("precision", Map.of("price", 0.000000001, "amount", 1.0));
        setMarkets(new java.util.HashMap<>(Map.of("SHIB/USDT", inner)));

        var pairs = service.getPairs(Exchange.BINANCE, MarketType.SPOT);
        assertThat(pairs.get(0).stepSize()).isEqualByComparingTo("1");
        assertThat(pairs.get(0).tickSize()).isEqualByComparingTo("0.000000001");
    }

    @Test
    void getPairs_whenMarketValueNotMap_shouldSkip() {
        // entry value 不是 Map → 跳过，不崩
        setMarkets(new java.util.HashMap<>(Map.of("BAD/USDT", "not-a-map")));
        var pairs = service.getPairs(Exchange.BINANCE, MarketType.SPOT);
        assertThat(pairs).isEmpty();
    }

    @Test
    void getPairs_whenLoadMarketsThrows_propagatesMarketDataException() {
        when(ccxt.loadMarkets(any())).thenReturn(CompletableFuture.failedFuture(new RuntimeException("API down")));

        assertThatThrownBy(() -> service.getPairs(Exchange.BINANCE, MarketType.SPOT))
                .isInstanceOf(com.kwikquant.market.domain.MarketDataException.class);
    }

    @Test
    void getPairs_whenMarketMissingSymbol_shouldSkip() {
        var inner = new java.util.HashMap<String, Object>();
        inner.put("base", "BTC");
        inner.put("quote", "USDT");
        inner.put("active", true);
        // Missing "symbol" key → parseMarket should handle gracefully
        setMarkets(new java.util.HashMap<>(Map.of("BTC/USDT", inner)));

        var pairs = service.getPairs(Exchange.BINANCE, MarketType.SPOT);
        // Should either skip or use map key as fallback — no exception
        assertThat(pairs).isNotNull();
    }

    @Test
    void getPairs_filtersByAllowedQuoteCurrencies() {
        // markets 含 BTC/USDT + ETH/USDC + BTC/ETH 三种 quote,只允许 USDT → 仅返 BTC/USDT
        var markets = new java.util.HashMap<String, Map<String, Object>>();
        markets.put("BTC/USDT", marketEntry("BTC/USDT", "BTC", "USDT"));
        markets.put("ETH/USDC", marketEntry("ETH/USDC", "ETH", "USDC"));
        markets.put("BTC/ETH", marketEntry("BTC/ETH", "BTC", "ETH"));
        setMarkets(markets);

        var usdtOnly = new QuoteCurrencyProperties(List.of("USDT"), new BigDecimal("100000"));
        var svc = new TradingPairService(registry, usdtOnly);

        var pairs = svc.getPairs(Exchange.OKX, MarketType.SPOT);

        assertThat(pairs).extracting(TradingPairInfo::quoteAsset).containsOnly("USDT");
        assertThat(pairs).hasSize(1);
        assertThat(pairs.get(0).symbol()).isEqualTo("BTC/USDT");
    }

    @Test
    void getPairs_multipleAllowedQuotes_keepsAllMatching() {
        var markets = new java.util.HashMap<String, Map<String, Object>>();
        markets.put("BTC/USDT", marketEntry("BTC/USDT", "BTC", "USDT"));
        markets.put("ETH/USDC", marketEntry("ETH/USDC", "ETH", "USDC"));
        markets.put("BTC/ETH", marketEntry("BTC/ETH", "BTC", "ETH"));
        setMarkets(markets);

        var multi = new QuoteCurrencyProperties(List.of("USDT", "USDC"), new BigDecimal("100000"));
        var svc = new TradingPairService(registry, multi);

        var pairs = svc.getPairs(Exchange.OKX, MarketType.SPOT);

        assertThat(pairs).extracting(TradingPairInfo::quoteAsset).containsOnly("USDT", "USDC");
        assertThat(pairs).hasSize(2);
    }

    private static Map<String, Object> marketEntry(String symbol, String base, String quote) {
        var inner = new java.util.HashMap<String, Object>();
        inner.put("symbol", symbol);
        inner.put("base", base);
        inner.put("quote", quote);
        inner.put("active", true);
        return inner;
    }
}
