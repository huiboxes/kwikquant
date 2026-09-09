package com.kwikquant.market.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * pair 规格装载单测。markets 经 {@link CcxtExchangeRegistry#marketsOfType}(type 过滤)注入,
 * 本测试聚焦 parseMarket 的币化换算与字段映射。
 */
class TradingPairServiceTest {

    private CcxtExchangeRegistry registry;
    private TradingPairService service;

    @BeforeEach
    void setUp() {
        registry = mock(CcxtExchangeRegistry.class);
        var props = new QuoteCurrencyProperties(List.of("USDT"), new BigDecimal("100000"));
        service = new TradingPairService(registry, props);
    }

    private void setMarkets(Exchange exchange, MarketType marketType, List<Map<?, ?>> markets) {
        when(registry.marketsOfType(exchange, marketType)).thenReturn(markets);
    }

    private static Map<String, Object> btcSpotMarket() {
        var m = new HashMap<String, Object>();
        m.put("symbol", "BTC/USDT");
        m.put("base", "BTC");
        m.put("quote", "USDT");
        m.put("active", true);
        m.put("limits", Map.of("amount", Map.of("min", 0.001, "max", 1000.0)));
        m.put("precision", Map.of("price", 0.01, "amount", 0.001));
        return m;
    }

    /** OKX BTC-USDT-SWAP 真实形态:ctVal=0.01、minSz=lotSz=0.01 张、tickSz=0.1、lever 上限 100。 */
    private static Map<String, Object> btcSwapMarket() {
        var m = new HashMap<String, Object>();
        m.put("symbol", "BTC/USDT:USDT");
        m.put("base", "BTC");
        m.put("quote", "USDT");
        m.put("type", "swap");
        m.put("active", true);
        m.put("contractSize", 0.01);
        m.put("limits", Map.of("amount", Map.of("min", 0.01, "max", 1000.0), "leverage", Map.of("max", 100.0)));
        m.put("precision", Map.of("price", 0.1, "amount", 0.01));
        return m;
    }

    // ---------- SPOT ----------

    @Test
    void getPairs_whenCacheMiss_shouldLoadFromRegistry() {
        setMarkets(Exchange.BINANCE, MarketType.SPOT, List.of(btcSpotMarket()));

        var pairs = service.getPairs(Exchange.BINANCE, MarketType.SPOT);

        assertThat(pairs).hasSize(1);
        var p = pairs.get(0);
        assertThat(p.symbol()).isEqualTo("BTC/USDT");
        assertThat(p.ccxtSymbol()).isEqualTo("BTC/USDT"); // SPOT canonical 同形
        assertThat(p.baseAsset()).isEqualTo("BTC");
        assertThat(p.quoteAsset()).isEqualTo("USDT");
        assertThat(p.active()).isTrue();
        assertThat(p.minQty()).isEqualByComparingTo("0.001");
        assertThat(p.maxQty()).isEqualByComparingTo("1000");
        // CCXT precision 统一返回 tick/step 值（E2E 验证 2026-06-29）
        assertThat(p.tickSize()).isEqualByComparingTo("0.01");
        assertThat(p.stepSize()).isEqualByComparingTo("0.001");
        assertThat(p.contractSize()).isEqualByComparingTo("1"); // SPOT 恒 1
        assertThat(p.maxLeverage()).isNull();
        verify(registry).marketsOfType(Exchange.BINANCE, MarketType.SPOT);
    }

    @Test
    void getPairs_whenCacheHit_shouldNotCallRegistry() {
        setMarkets(Exchange.BINANCE, MarketType.SPOT, List.of(btcSpotMarket()));

        service.getPairs(Exchange.BINANCE, MarketType.SPOT);
        service.getPairs(Exchange.BINANCE, MarketType.SPOT);

        // Caffeine 命中 → marketsOfType 只调 1 次
        verify(registry, times(1)).marketsOfType(Exchange.BINANCE, MarketType.SPOT);
    }

    @Test
    void getPairs_whenDifferentCacheKey_shouldLoadSeparately() {
        setMarkets(Exchange.BINANCE, MarketType.SPOT, List.of(btcSpotMarket()));
        setMarkets(Exchange.BINANCE, MarketType.PERP, List.of(btcSwapMarket()));

        service.getPairs(Exchange.BINANCE, MarketType.SPOT);
        service.getPairs(Exchange.BINANCE, MarketType.PERP);

        verify(registry, times(1)).marketsOfType(Exchange.BINANCE, MarketType.SPOT);
        verify(registry, times(1)).marketsOfType(Exchange.BINANCE, MarketType.PERP);
    }

    @Test
    void getPairs_whenMarketInactive_shouldFilterOut() {
        var inactive = btcSpotMarket();
        inactive.put("active", false);
        setMarkets(Exchange.BINANCE, MarketType.SPOT, List.of(inactive));

        assertThat(service.getPairs(Exchange.BINANCE, MarketType.SPOT)).isEmpty();
    }

    @Test
    void getPairs_whenMarketMissingLimitsAndPrecision_shouldStillParse() {
        var inner = new HashMap<String, Object>();
        inner.put("symbol", "BTC/USDT");
        inner.put("base", "BTC");
        inner.put("quote", "USDT");
        inner.put("active", true);
        setMarkets(Exchange.BINANCE, MarketType.SPOT, List.of(inner));

        var pairs = service.getPairs(Exchange.BINANCE, MarketType.SPOT);
        assertThat(pairs).hasSize(1);
        assertThat(pairs.get(0).minQty()).isNull();
        assertThat(pairs.get(0).tickSize()).isNull();
    }

    @Test
    void getPairs_whenPrecisionIsOne_shouldReturnOneNotZeroPointOne() {
        // OKX SHIB/USDT: precision.amount=1.0（步长=1，整数量交易）
        var inner = new HashMap<String, Object>();
        inner.put("symbol", "SHIB/USDT");
        inner.put("base", "SHIB");
        inner.put("quote", "USDT");
        inner.put("active", true);
        inner.put("precision", Map.of("price", 0.000000001, "amount", 1.0));
        setMarkets(Exchange.BINANCE, MarketType.SPOT, List.of(inner));

        var pairs = service.getPairs(Exchange.BINANCE, MarketType.SPOT);
        assertThat(pairs.get(0).stepSize()).isEqualByComparingTo("1");
        assertThat(pairs.get(0).tickSize()).isEqualByComparingTo("0.000000001");
    }

    @Test
    void getPairs_whenMarketMissingSymbolOrBaseQuote_shouldSkip() {
        var noSymbol = new HashMap<String, Object>();
        noSymbol.put("base", "BTC");
        noSymbol.put("quote", "USDT");
        var noBase = new HashMap<String, Object>();
        noBase.put("symbol", "ETH/USDT");
        noBase.put("quote", "USDT");
        setMarkets(Exchange.BINANCE, MarketType.SPOT, List.of(noSymbol, noBase));

        assertThat(service.getPairs(Exchange.BINANCE, MarketType.SPOT)).isEmpty();
    }

    @Test
    void getPairs_whenRegistryLoadFails_propagatesMarketDataException() {
        when(registry.marketsOfType(Exchange.BINANCE, MarketType.SPOT))
                .thenThrow(new CompletionException(new RuntimeException("API down")));

        assertThatThrownBy(() -> service.getPairs(Exchange.BINANCE, MarketType.SPOT))
                .isInstanceOf(com.kwikquant.market.domain.MarketDataException.class)
                .hasMessageContaining("failed loading markets");
    }

    // ---------- PERP:张→币化 + canonical/ccxtSymbol 分离 ----------

    /**
     * PERP 装载即币化:minSz/lotSz/maxSz(张)× ctVal → 币;tickSz(价格精度)不换算。
     * symbol 归一 canonical(BTC/USDT),CCXT unified(BTC/USDT:USDT)另存 ccxtSymbol。
     */
    @Test
    void getPairs_perp_coinifiesContractSpecs() {
        setMarkets(Exchange.OKX, MarketType.PERP, List.of(btcSwapMarket()));

        var pairs = service.getPairs(Exchange.OKX, MarketType.PERP);

        assertThat(pairs).hasSize(1);
        var p = pairs.get(0);
        assertThat(p.symbol()).isEqualTo("BTC/USDT");
        assertThat(p.ccxtSymbol()).isEqualTo("BTC/USDT:USDT");
        assertThat(p.contractSize()).isEqualByComparingTo("0.01");
        // minSz=0.01 张 × 0.01 = 0.0001 BTC;lotSz 同;maxSz=1000 张 × 0.01 = 10 BTC
        assertThat(p.minQty()).isEqualByComparingTo("0.0001");
        assertThat(p.stepSize()).isEqualByComparingTo("0.0001");
        assertThat(p.maxQty()).isEqualByComparingTo("10");
        // tickSz=0.1 是价格精度,与合约尺寸无关
        assertThat(p.tickSize()).isEqualByComparingTo("0.1");
        assertThat(p.maxLeverage()).isEqualTo(100);
    }

    /** PERP 缺 contractSize → 整条跳过(fail-closed:张币换算语义破坏的 pair 进域内必错钱)。 */
    @Test
    void getPairs_perpMissingContractSize_skipped() {
        var noCtVal = btcSwapMarket();
        noCtVal.remove("contractSize");
        setMarkets(Exchange.OKX, MarketType.PERP, List.of(noCtVal, btcSwapMarket()));

        var pairs = service.getPairs(Exchange.OKX, MarketType.PERP);

        assertThat(pairs).hasSize(1); // 仅完整条目存活
        assertThat(pairs.get(0).ccxtSymbol()).isEqualTo("BTC/USDT:USDT");
    }

    /**
     * SPOT 忽略 limits.leverage 声明:CCXT 对 OKX spot 无条件设 max=stringMax(lever,'1')=1,
     * 读取会把"1"当真实上限污染语义(曾经导致一切 leverage>1 的 PERP 单被误拒的根源之一)。
     */
    @Test
    void getPairs_spot_ignoresLeverageDeclaration() {
        var spot = btcSpotMarket();
        spot.put("limits", Map.of("amount", Map.of("min", 0.001), "leverage", Map.of("max", 1.0)));
        setMarkets(Exchange.OKX, MarketType.SPOT, List.of(spot));

        var pairs = service.getPairs(Exchange.OKX, MarketType.SPOT);

        assertThat(pairs.get(0).maxLeverage()).isNull();
    }

    /** PERP leverage.max ≤0(脏数据)→ 视为未声明 null(Order.validate 对 PERP fail-closed 拒单)。 */
    @Test
    void getPairs_perpNonPositiveLeverage_treatedAsUndeclared() {
        var swap = btcSwapMarket();
        swap.put("limits", Map.of("amount", Map.of("min", 0.01), "leverage", Map.of("max", 0.0)));
        setMarkets(Exchange.OKX, MarketType.PERP, List.of(swap));

        var pairs = service.getPairs(Exchange.OKX, MarketType.PERP);

        assertThat(pairs.get(0).maxLeverage()).isNull();
    }

    // ---------- quote 白名单 ----------

    @Test
    void getPairs_filtersByAllowedQuoteCurrencies() {
        when(registry.marketsOfType(Exchange.OKX, MarketType.SPOT))
                .thenReturn(List.of(
                        marketEntry("BTC/USDT", "BTC", "USDT"),
                        marketEntry("ETH/USDC", "ETH", "USDC"),
                        marketEntry("BTC/ETH", "BTC", "ETH")));

        var pairs = service.getPairs(Exchange.OKX, MarketType.SPOT);

        assertThat(pairs).extracting(TradingPairInfo::quoteAsset).containsOnly("USDT");
        assertThat(pairs).hasSize(1);
        assertThat(pairs.get(0).symbol()).isEqualTo("BTC/USDT");
    }

    @Test
    void getPairs_multipleAllowedQuotes_keepsAllMatching() {
        when(registry.marketsOfType(Exchange.OKX, MarketType.SPOT))
                .thenReturn(List.of(
                        marketEntry("BTC/USDT", "BTC", "USDT"),
                        marketEntry("ETH/USDC", "ETH", "USDC"),
                        marketEntry("BTC/ETH", "BTC", "ETH")));
        var multi = new QuoteCurrencyProperties(List.of("USDT", "USDC"), new BigDecimal("100000"));
        var svc = new TradingPairService(registry, multi);

        var pairs = svc.getPairs(Exchange.OKX, MarketType.SPOT);

        assertThat(pairs).extracting(TradingPairInfo::quoteAsset).containsOnly("USDT", "USDC");
        assertThat(pairs).hasSize(2);
    }

    private static Map<String, Object> marketEntry(String symbol, String base, String quote) {
        var inner = new HashMap<String, Object>();
        inner.put("symbol", symbol);
        inner.put("base", base);
        inner.put("quote", quote);
        inner.put("active", true);
        return inner;
    }
}
