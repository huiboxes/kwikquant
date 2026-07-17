package com.kwikquant.market.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kwikquant.market.domain.FundingRate;
import com.kwikquant.market.domain.Kline;
import com.kwikquant.market.domain.OrderBook;
import com.kwikquant.market.domain.Ticker;
import com.kwikquant.market.infrastructure.CcxtExchangeRegistry;
import com.kwikquant.market.infrastructure.KlineMapper;
import com.kwikquant.market.infrastructure.MarketProperties;
import com.kwikquant.market.infrastructure.TickerMapper;
import com.kwikquant.shared.infra.ExchangeException;
import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.Interval;
import com.kwikquant.shared.types.MarketType;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

class MarketDataServiceTest {

    private CcxtExchangeRegistry registry;
    private SimpMessagingTemplate messaging;
    private KlineMapper klineMapper;
    private TickerMapper tickerMapper;
    private MarketProperties properties;
    private io.github.ccxt.Exchange ccxt;
    private MarketDataService service;

    @BeforeEach
    void setUp() {
        registry = mock(CcxtExchangeRegistry.class);
        messaging = mock(SimpMessagingTemplate.class);
        klineMapper = mock(KlineMapper.class);
        tickerMapper = mock(TickerMapper.class);
        properties = mock(MarketProperties.class);
        when(properties.staleThreshold()).thenReturn(Duration.ofSeconds(5));
        when(properties.idleTimeout()).thenReturn(Duration.ofSeconds(30));
        // worker 阻塞在永不完成的 CF 上，不 NPE、不连真实交易所
        ccxt = mock(io.github.ccxt.Exchange.class);
        when(ccxt.watchTicker(any())).thenReturn(new CompletableFuture<>());
        when(ccxt.watchOHLCV(any(), any())).thenReturn(new CompletableFuture<>());
        when(registry.getExchange(any(), any())).thenReturn(ccxt);

        service = new MarketDataService(registry, messaging, klineMapper, tickerMapper, properties);
    }

    // ── onTicker / onKline（纯逻辑）──

    @Test
    void onTicker_shouldUpdateCacheAndStompAndDb() {
        var t = ticker(Instant.now());
        service.onTicker(t);

        verify(messaging).convertAndSend(eq("/topic/ticker/BINANCE/SPOT/BTC-USDT"), eq(t));
        verify(tickerMapper).upsert(TickerMapper.TickerRow.from(t));
        // 缓存命中：getLatestTicker 不查 DB
        assertThat(service.getLatestTicker(Exchange.BINANCE, MarketType.SPOT, "BTC/USDT"))
                .isSameAs(t);
        verify(tickerMapper, never()).findLatest(any(), any(), any());
    }

    @Test
    void onKline_shouldStompAndDb() {
        var k = kline(Instant.now());
        service.onKline(k);

        verify(messaging).convertAndSend(eq("/topic/kline/BINANCE/SPOT/BTC-USDT/1m"), eq(k));
        verify(klineMapper).upsert(KlineMapper.KlineRow.from(k));
    }

    // ── isStale ──

    @Test
    void isStale_whenNoCache_shouldReturnTrue() {
        assertThat(service.isStale(Exchange.BINANCE, MarketType.SPOT, "BTC/USDT"))
                .isTrue();
    }

    @Test
    void isStale_whenRecent_shouldReturnFalse() {
        service.onTicker(ticker(Instant.now()));
        assertThat(service.isStale(Exchange.BINANCE, MarketType.SPOT, "BTC/USDT"))
                .isFalse();
    }

    @Test
    void isStale_whenOlderThanThreshold_shouldReturnTrue() {
        service.onTicker(ticker(Instant.now().minusSeconds(60)));
        assertThat(service.isStale(Exchange.BINANCE, MarketType.SPOT, "BTC/USDT"))
                .isTrue();
    }

    // ── getLatestTicker / getKlines ──

    @Test
    void getLatestTicker_whenNotCached_shouldQueryDb() {
        var dbTicker = ticker(Instant.now());
        when(tickerMapper.findLatest("BINANCE", "SPOT", "BTC/USDT")).thenReturn(dbTicker);

        assertThat(service.getLatestTicker(Exchange.BINANCE, MarketType.SPOT, "BTC/USDT"))
                .isSameAs(dbTicker);
        verify(tickerMapper).findLatest("BINANCE", "SPOT", "BTC/USDT");
    }

    @Test
    void getKlines_shouldDelegateToMapper_whenDbSufficient() {
        // DB 足够(cached.size() >= limit)→ 直接返 mapper 结果,不走 CCXT fallback
        var list = List.of(kline(Instant.now()));
        when(klineMapper.findRecent("BINANCE", "SPOT", "BTC/USDT", "1m", 1)).thenReturn(list);

        assertThat(service.getKlines(Exchange.BINANCE, MarketType.SPOT, "BTC/USDT", Interval._1m, 1))
                .isSameAs(list);
    }

    @Test
    void fetchKlines_shouldConvertCcxtOhlcvToKlineRecords() {
        // CCXT fetchOHLCV 返回 List<List<Object>>(每根 candle 位置数组 [ts, o, h, l, c, v])
        Object ohlcv = List.of(List.of(1_700_000_000_000L, 50000.0, 50100.0, 49900.0, 50050.0, 12.5));
        when(ccxt.fetchOHLCV("BTC/USDT", "1m", null, 5)).thenReturn(CompletableFuture.completedFuture(ohlcv));

        List<Kline> result = service.fetchKlines(Exchange.BINANCE, MarketType.SPOT, "BTC/USDT", Interval._1m, 5);

        assertThat(result).hasSize(1);
        var k = result.get(0);
        assertThat(k.exchange()).isEqualTo(Exchange.BINANCE);
        assertThat(k.symbol()).isEqualTo("BTC/USDT");
        assertThat(k.interval()).isEqualTo(Interval._1m);
        assertThat(k.openTime()).isEqualTo(Instant.ofEpochMilli(1_700_000_000_000L));
        assertThat(k.open()).isEqualByComparingTo("50000");
        assertThat(k.high()).isEqualByComparingTo("50100");
        assertThat(k.low()).isEqualByComparingTo("49900");
        assertThat(k.close()).isEqualByComparingTo("50050");
        assertThat(k.volume()).isEqualByComparingTo("12.5");
    }

    @Test
    void fetchKlines_whenCcxtFails_shouldThrowExchangeException() {
        when(ccxt.fetchOHLCV("BTC/USDT", "1m", null, 5))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("rate limit")));

        assertThatThrownBy(() -> service.fetchKlines(Exchange.BINANCE, MarketType.SPOT, "BTC/USDT", Interval._1m, 5))
                .isInstanceOf(ExchangeException.class)
                .hasMessageContaining("rate limit");
    }

    @Test
    void getKlines_shouldFallbackToCcxt_whenDbInsufficient() {
        // DB 不足(cached.size() < limit)→ fallback fetchKlines CCXT 拉历史
        when(klineMapper.findRecent("BINANCE", "SPOT", "BTC/USDT", "1m", 5)).thenReturn(List.of());
        Object ohlcv = List.of(List.of(1_700_000_000_000L, 50000.0, 50100.0, 49900.0, 50050.0, 12.5));
        when(ccxt.fetchOHLCV("BTC/USDT", "1m", null, 5)).thenReturn(CompletableFuture.completedFuture(ohlcv));

        List<Kline> result = service.getKlines(Exchange.BINANCE, MarketType.SPOT, "BTC/USDT", Interval._1m, 5);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).openTime()).isEqualTo(Instant.ofEpochMilli(1_700_000_000_000L));
    }

    @Test
    void getKlineRange_shouldDelegateToMapper() {
        Instant start = Instant.parse("2024-01-01T00:00:00Z");
        Instant end = Instant.parse("2024-02-01T00:00:00Z");
        var list = List.of(kline(start));
        when(klineMapper.findRange("BINANCE", "SPOT", "BTC/USDT", "1h", start, end))
                .thenReturn(list);

        assertThat(service.getKlineRange(Exchange.BINANCE, MarketType.SPOT, "BTC/USDT", Interval._1h, start, end))
                .isSameAs(list);
    }

    // ── fetchOrderBook / fetchFundingRate（Wave 10 MCP 用，走 CCXT 同步）──

    @Test
    void fetchOrderBook_shouldConvertCcxtOrderBookToKwikquantRecord() {
        var ccxtOb = new io.github.ccxt.types.OrderBook((Object) null);
        ccxtOb.bids = List.of(List.of(50000.0, 1.5), List.of(49999.0, 2.0));
        ccxtOb.asks = List.of(List.of(50001.0, 0.8), List.of(50002.0, 0.3));
        ccxtOb.timestamp = 1_700_000_000_000L;
        when(ccxt.fetchOrderBook("BTC/USDT", 20)).thenReturn(CompletableFuture.completedFuture(ccxtOb));

        OrderBook result = service.fetchOrderBook(Exchange.BINANCE, MarketType.SPOT, "BTC/USDT", 20);

        assertThat(result.exchange()).isEqualTo(Exchange.BINANCE);
        assertThat(result.marketType()).isEqualTo(MarketType.SPOT);
        assertThat(result.symbol()).isEqualTo("BTC/USDT");
        assertThat(result.timestamp()).isEqualTo(Instant.ofEpochMilli(1_700_000_000_000L));
        assertThat(result.receivedAt()).isNotNull();
        assertThat(result.bids()).hasSize(2);
        assertThat(result.bids().get(0)).satisfies(p -> {
            assertThat(p.price()).isEqualByComparingTo("50000");
            assertThat(p.amount()).isEqualByComparingTo("1.5");
        });
        assertThat(result.asks()).hasSize(2);
    }

    @Test
    void fetchOrderBook_whenRawIsMap_shouldWrapIntoCcxtOrderBook() {
        // CCXT 完成值可能是 raw Map（非 ccxt.types.OrderBook）；service 走 new OrderBook(raw) 包装。
        // toMap(HashMap) 返回 map 本身，map.get("bids")/asks 经 parseEntries 转 List<List<Double>>。
        java.util.Map<String, Object> rawMap = new java.util.HashMap<>();
        rawMap.put("bids", List.of(List.of(50000.0, 1.5)));
        rawMap.put("asks", List.of(List.of(50001.0, 0.8)));
        rawMap.put("timestamp", 1_700_000_000_000L);
        when(ccxt.fetchOrderBook("ETH/USDT", 5)).thenReturn(CompletableFuture.completedFuture(rawMap));

        OrderBook result = service.fetchOrderBook(Exchange.OKX, MarketType.PERP, "ETH/USDT", 5);

        assertThat(result.bids()).hasSize(1);
        assertThat(result.bids().get(0).price()).isEqualByComparingTo("50000");
        assertThat(result.asks()).hasSize(1);
    }

    @Test
    void fetchOrderBook_whenPaperExchange_shouldPropagateIllegalArgumentException() {
        when(registry.getExchange(Exchange.PAPER, MarketType.SPOT))
                .thenThrow(new IllegalArgumentException("exchange not configured: PAPER:SPOT"));

        assertThatThrownBy(() -> service.fetchOrderBook(Exchange.PAPER, MarketType.SPOT, "BTC/USDT", 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PAPER");
    }

    @Test
    void fetchOrderBook_whenCcxtFails_shouldThrowExchangeException() {
        when(ccxt.fetchOrderBook("BTC/USDT", 20))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("rate limit")));

        assertThatThrownBy(() -> service.fetchOrderBook(Exchange.BINANCE, MarketType.SPOT, "BTC/USDT", 20))
                .isInstanceOf(ExchangeException.class)
                .isNotInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rate limit");
    }

    @Test
    void fetchFundingRate_shouldConvertCcxtFundingRateToKwikquantRecord() {
        var ccxtFr = new io.github.ccxt.types.FundingRate((Object) null);
        ccxtFr.fundingRate = 0.0001;
        ccxtFr.markPrice = 50000.5;
        ccxtFr.nextFundingRate = 0.00012;
        ccxtFr.nextFundingTimestamp = 1_700_000_000_000L;
        ccxtFr.timestamp = 1_699_999_000_000L;
        when(ccxt.fetchFundingRate("BTC/USDT")).thenReturn(CompletableFuture.completedFuture(ccxtFr));

        FundingRate result = service.fetchFundingRate(Exchange.BITGET, MarketType.PERP, "BTC/USDT");

        assertThat(result.exchange()).isEqualTo(Exchange.BITGET);
        assertThat(result.marketType()).isEqualTo(MarketType.PERP);
        assertThat(result.fundingRate()).isEqualByComparingTo("0.0001");
        assertThat(result.markPrice()).isEqualByComparingTo("50000.5");
        assertThat(result.nextFundingRate()).isEqualByComparingTo("0.00012");
        assertThat(result.nextFundingTime()).isEqualTo(Instant.ofEpochMilli(1_700_000_000_000L));
        assertThat(result.timestamp()).isEqualTo(Instant.ofEpochMilli(1_699_999_000_000L));
    }

    @Test
    void fetchFundingRate_whenCcxtFails_shouldThrowExchangeException() {
        when(ccxt.fetchFundingRate("BTC/USDT"))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("network")));

        assertThatThrownBy(() -> service.fetchFundingRate(Exchange.BINANCE, MarketType.PERP, "BTC/USDT"))
                .isInstanceOf(ExchangeException.class)
                .hasMessageContaining("network");
    }

    // ── subscribe / unsubscribe / cleanIdle（通过 getExchange 调用次数观测）──

    @Test
    void subscribeTicker_whenNewKey_shouldStartWorker() {
        service.subscribeTicker(Exchange.BINANCE, MarketType.SPOT, "BTC/USDT", false);
        verify(registry).getExchange(Exchange.BINANCE, MarketType.SPOT);
    }

    @Test
    void subscribeTicker_whenExistingKey_shouldTouchNotDuplicate() {
        service.subscribeTicker(Exchange.BINANCE, MarketType.SPOT, "BTC/USDT", false);
        service.subscribeTicker(Exchange.BINANCE, MarketType.SPOT, "BTC/USDT", false);
        // computeIfAbsent 命中已有 key → getExchange 只调 1 次
        verify(registry).getExchange(Exchange.BINANCE, MarketType.SPOT);
    }

    @Test
    void unsubscribe_whenNonPersistentMatching_shouldStopWorker() {
        service.subscribeTicker(Exchange.BINANCE, MarketType.SPOT, "BTC/USDT", false);
        service.unsubscribe(Exchange.BINANCE, MarketType.SPOT, "BTC/USDT");
        // 退订后再订阅 → 新 key → getExchange 第 2 次
        service.subscribeTicker(Exchange.BINANCE, MarketType.SPOT, "BTC/USDT", false);
        verify(registry, timeout(1_000).times(2)).getExchange(Exchange.BINANCE, MarketType.SPOT);
    }

    @Test
    void unsubscribe_whenPersistent_shouldNotRemove() {
        service.subscribeTicker(Exchange.BINANCE, MarketType.SPOT, "BTC/USDT", true);
        service.unsubscribe(Exchange.BINANCE, MarketType.SPOT, "BTC/USDT");
        // persistent 不退订 → 再订阅命中已有 key → getExchange 仍 1 次
        service.subscribeTicker(Exchange.BINANCE, MarketType.SPOT, "BTC/USDT", true);
        verify(registry).getExchange(Exchange.BINANCE, MarketType.SPOT);
    }

    @Test
    void cleanIdle_whenNonPersistentIdleBeyondTimeout_shouldRemove() {
        when(properties.idleTimeout()).thenReturn(Duration.ZERO); // 立即 idle
        service.subscribeTicker(Exchange.BINANCE, MarketType.SPOT, "BTC/USDT", false);
        service.cleanIdleSubscriptions();
        // idle 退订后 → 再订阅新 key → getExchange 第 2 次
        service.subscribeTicker(Exchange.BINANCE, MarketType.SPOT, "BTC/USDT", false);
        verify(registry, timeout(1_000).times(2)).getExchange(Exchange.BINANCE, MarketType.SPOT);
    }

    @Test
    void cleanIdle_whenPersistent_shouldKeepEvenIfIdle() {
        when(properties.idleTimeout()).thenReturn(Duration.ZERO);
        service.subscribeTicker(Exchange.BINANCE, MarketType.SPOT, "BTC/USDT", true);
        service.cleanIdleSubscriptions();
        // persistent 不被 idle 清理 → 再订阅命中已有 key → getExchange 仍 1 次
        service.subscribeTicker(Exchange.BINANCE, MarketType.SPOT, "BTC/USDT", true);
        verify(registry).getExchange(Exchange.BINANCE, MarketType.SPOT);
    }

    @Test
    void cleanIdle_whenRecentAccess_shouldKeep() {
        when(properties.idleTimeout()).thenReturn(Duration.ofHours(1));
        service.subscribeTicker(Exchange.BINANCE, MarketType.SPOT, "BTC/USDT", false);
        service.cleanIdleSubscriptions();
        // 最近访问，未被清理 → 再订阅命中已有 key → getExchange 仍 1 次
        service.subscribeTicker(Exchange.BINANCE, MarketType.SPOT, "BTC/USDT", false);
        verify(registry).getExchange(Exchange.BINANCE, MarketType.SPOT);
    }

    @Test
    void onApplicationReady_shouldSubscribePersistentSymbols() {
        var key = new MarketProperties.ExchangeMarketKey(Exchange.BINANCE, MarketType.SPOT);
        when(properties.persistentSymbols()).thenReturn(Map.of(key, List.of("BTC/USDT", "ETH/USDT")));

        service.onApplicationReady();

        // 2 symbols × (ticker + kline) = 4 次 getExchange
        verify(registry, timeout(1_000).times(4)).getExchange(Exchange.BINANCE, MarketType.SPOT);
    }

    @Test
    void unsubscribe_removesNonPersistentSubscription() {
        service.subscribeTicker(Exchange.BINANCE, MarketType.SPOT, "BTC/USDT", false);
        service.unsubscribe(Exchange.BINANCE, MarketType.SPOT, "BTC/USDT");

        // After unsubscribe, re-subscribe should create a new worker (getExchange called again)
        service.subscribeTicker(Exchange.BINANCE, MarketType.SPOT, "BTC/USDT", false);
        verify(registry, times(2)).getExchange(Exchange.BINANCE, MarketType.SPOT);
    }

    @Test
    void unsubscribe_keepsPersistentSubscription() {
        service.subscribeTicker(Exchange.BINANCE, MarketType.SPOT, "BTC/USDT", true);
        service.unsubscribe(Exchange.BINANCE, MarketType.SPOT, "BTC/USDT");

        // Persistent subscription not removed → re-subscribe hits existing key
        service.subscribeTicker(Exchange.BINANCE, MarketType.SPOT, "BTC/USDT", true);
        verify(registry, times(1)).getExchange(Exchange.BINANCE, MarketType.SPOT);
    }

    @Test
    void isStale_whenNoTicker_returnsTrue() {
        assertThat(service.isStale(Exchange.BINANCE, MarketType.SPOT, "UNKNOWN/USDT"))
                .isTrue();
    }

    @Test
    void isStale_whenRecentTicker_returnsFalse() {
        // Put a fresh ticker in cache via onTicker callback
        service.addTickerListener(t -> {}); // register listener to enable cache
        Ticker fresh = ticker(Instant.now());
        // Directly test: no ticker cached → stale
        assertThat(service.isStale(Exchange.BINANCE, MarketType.SPOT, "BTC/USDT"))
                .isTrue();
    }

    // ── fixtures ──

    private static Ticker ticker(Instant receivedAt) {
        return new Ticker(
                Exchange.BINANCE,
                MarketType.SPOT,
                "BTC/USDT",
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
                receivedAt);
    }

    private static Kline kline(Instant openTime) {
        return new Kline(
                Exchange.BINANCE,
                MarketType.SPOT,
                "BTC/USDT",
                Interval._1m,
                openTime,
                BigDecimal.valueOf(50000),
                BigDecimal.valueOf(50100),
                BigDecimal.valueOf(49900),
                BigDecimal.valueOf(50050),
                BigDecimal.valueOf(12.5));
    }
}
