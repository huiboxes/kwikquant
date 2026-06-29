package com.kwikquant.market.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kwikquant.market.domain.Kline;
import com.kwikquant.market.domain.Ticker;
import com.kwikquant.market.infrastructure.CcxtExchangeRegistry;
import com.kwikquant.market.infrastructure.KlineMapper;
import com.kwikquant.market.infrastructure.MarketProperties;
import com.kwikquant.market.infrastructure.TickerMapper;
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
        var ccxt = mock(io.github.ccxt.Exchange.class);
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
    void getKlines_shouldDelegateToMapper() {
        var list = List.of(kline(Instant.now()));
        when(klineMapper.findRecent("BINANCE", "SPOT", "BTC/USDT", "1m", 100)).thenReturn(list);

        assertThat(service.getKlines(Exchange.BINANCE, MarketType.SPOT, "BTC/USDT", Interval._1m, 100))
                .isSameAs(list);
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
