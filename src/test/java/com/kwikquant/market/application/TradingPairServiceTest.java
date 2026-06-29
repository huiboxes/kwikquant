package com.kwikquant.market.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kwikquant.market.infrastructure.CcxtExchangeRegistry;
import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.MarketType;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TradingPairServiceTest {

    private CcxtExchangeRegistry registry;
    private io.github.ccxt.Exchange ccxt;
    private TradingPairService service;

    @BeforeEach
    void setUp() {
        registry = mock(CcxtExchangeRegistry.class);
        ccxt = mock(io.github.ccxt.Exchange.class);
        when(ccxt.loadMarkets(any())).thenReturn(CompletableFuture.completedFuture(null));
        when(registry.getExchange(any(), any())).thenReturn(ccxt);
        service = new TradingPairService(registry);
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
                        Map.of("price", 2, "amount", 3)));
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
        // precision.price=2（小数位数）→ 10^(-2) = 0.01
        assertThat(p.tickSize()).isEqualByComparingTo("0.01");
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
        // precision.price = 0.01（已是 tick 值，p<1）→ 直接用
        var inner = new java.util.HashMap<String, Object>();
        inner.put("symbol", "BTC/USDT");
        inner.put("active", true);
        inner.put("precision", Map.of("price", 0.01, "amount", 0.001));
        setMarkets(new java.util.HashMap<>(Map.of("BTC/USDT", inner)));

        var pairs = service.getPairs(Exchange.BINANCE, MarketType.SPOT);
        assertThat(pairs.get(0).tickSize()).isEqualByComparingTo("0.01");
        assertThat(pairs.get(0).stepSize()).isEqualByComparingTo("0.001");
    }

    @Test
    void getPairs_whenMarketValueNotMap_shouldSkip() {
        // entry value 不是 Map → 跳过，不崩
        setMarkets(new java.util.HashMap<>(Map.of("BAD/USDT", "not-a-map")));
        var pairs = service.getPairs(Exchange.BINANCE, MarketType.SPOT);
        assertThat(pairs).isEmpty();
    }
}
