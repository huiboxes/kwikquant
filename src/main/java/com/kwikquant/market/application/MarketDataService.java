package com.kwikquant.market.application;

import com.kwikquant.market.domain.Kline;
import com.kwikquant.market.domain.Ticker;
import com.kwikquant.market.infrastructure.CcxtExchangeRegistry;
import com.kwikquant.market.infrastructure.CcxtKlineWorker;
import com.kwikquant.market.infrastructure.CcxtTickerWorker;
import com.kwikquant.market.infrastructure.KlineMapper;
import com.kwikquant.market.infrastructure.MarketProperties;
import com.kwikquant.market.infrastructure.Stoppable;
import com.kwikquant.market.infrastructure.TickerMapper;
import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.Interval;
import com.kwikquant.shared.types.MarketType;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class MarketDataService {

    private static final Logger log = LoggerFactory.getLogger(MarketDataService.class);

    private final CcxtExchangeRegistry exchangeRegistry;
    private final SimpMessagingTemplate messagingTemplate;
    private final KlineMapper klineMapper;
    private final TickerMapper tickerMapper;
    private final MarketProperties properties;

    private final ConcurrentMap<SubscriptionKey, SubscriptionState> subscriptions = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Ticker> latestTickers = new ConcurrentHashMap<>();

    public MarketDataService(
            CcxtExchangeRegistry exchangeRegistry,
            SimpMessagingTemplate messagingTemplate,
            KlineMapper klineMapper,
            TickerMapper tickerMapper,
            MarketProperties properties) {
        this.exchangeRegistry = exchangeRegistry;
        this.messagingTemplate = messagingTemplate;
        this.klineMapper = klineMapper;
        this.tickerMapper = tickerMapper;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    void onApplicationReady() {
        for (var entry : properties.persistentSymbols().entrySet()) {
            Exchange exchange = entry.getKey().exchange();
            MarketType marketType = entry.getKey().marketType();
            for (String symbol : entry.getValue()) {
                // 单个 symbol 订阅失败（如 exchange 未配置）不阻断其余 symbol 的订阅
                try {
                    subscribeTicker(exchange, marketType, symbol, true);
                    subscribeKline(exchange, marketType, symbol, Interval._1m, true);
                } catch (RuntimeException e) {
                    log.warn(
                            "failed to subscribe persistent symbol {}.{}.{}: {}",
                            exchange,
                            marketType,
                            symbol,
                            e.getMessage());
                }
            }
        }
        log.info("market data service started, persistent subscriptions: {}", subscriptions.size());
    }

    public void subscribeTicker(Exchange exchange, MarketType marketType, String symbol, boolean persistent) {
        var key = new SubscriptionKey(exchange, marketType, symbol, "ticker", null);
        subscriptions
                .computeIfAbsent(key, k -> {
                    var worker = new CcxtTickerWorker(
                            exchangeRegistry.getExchange(exchange, marketType),
                            symbol,
                            this::onTicker,
                            exchange,
                            marketType);
                    worker.start();
                    log.info("subscribed ticker: {}.{}.{}", exchange, marketType, symbol);
                    return new SubscriptionState(worker, persistent, Instant.now());
                })
                .touch();
    }

    public void subscribeKline(
            Exchange exchange, MarketType marketType, String symbol, Interval interval, boolean persistent) {
        var key = new SubscriptionKey(exchange, marketType, symbol, "kline", interval);
        subscriptions
                .computeIfAbsent(key, k -> {
                    var worker = new CcxtKlineWorker(
                            exchangeRegistry.getExchange(exchange, marketType),
                            symbol,
                            interval,
                            this::onKline,
                            exchange,
                            marketType);
                    worker.start();
                    log.info("subscribed kline: {}.{}.{} {}", exchange, marketType, symbol, interval);
                    return new SubscriptionState(worker, persistent, Instant.now());
                })
                .touch();
    }

    public void unsubscribe(Exchange exchange, MarketType marketType, String symbol) {
        subscriptions.entrySet().removeIf(entry -> {
            var k = entry.getKey();
            if (k.exchange() == exchange
                    && k.marketType() == marketType
                    && k.symbol().equals(symbol)
                    && !entry.getValue().persistent()) {
                entry.getValue().worker().stop();
                log.info("unsubscribed: {}.{}.{}", exchange, marketType, symbol);
                return true;
            }
            return false;
        });
    }

    @Scheduled(fixedDelay = 10_000)
    void cleanIdleSubscriptions() {
        Duration idleThreshold = properties.idleTimeout();
        Instant cutoff = Instant.now().minus(idleThreshold);
        subscriptions.entrySet().removeIf(entry -> {
            var state = entry.getValue();
            if (!state.persistent() && state.lastAccess().isBefore(cutoff)) {
                state.worker().stop();
                log.info("idle-unsubscribed: {}", entry.getKey());
                return true;
            }
            return false;
        });
    }

    void onTicker(Ticker ticker) {
        String cacheKey = tickerCacheKey(ticker.exchange(), ticker.marketType(), ticker.symbol());
        latestTickers.put(cacheKey, ticker);

        String destination = String.format(
                "/topic/ticker/%s/%s/%s",
                ticker.exchange(), ticker.marketType(), ticker.symbol().replace("/", "-"));
        messagingTemplate.convertAndSend(destination, ticker);

        tickerMapper.upsert(TickerMapper.TickerRow.from(ticker));
    }

    void onKline(Kline kline) {
        String destination = String.format(
                "/topic/kline/%s/%s/%s/%s",
                kline.exchange(),
                kline.marketType(),
                kline.symbol().replace("/", "-"),
                kline.interval().ccxtValue());
        messagingTemplate.convertAndSend(destination, kline);

        klineMapper.upsert(KlineMapper.KlineRow.from(kline));
    }

    public boolean isStale(Exchange exchange, MarketType marketType, String symbol) {
        String key = tickerCacheKey(exchange, marketType, symbol);
        Ticker t = latestTickers.get(key);
        if (t == null) return true;
        return Duration.between(t.receivedAt(), Instant.now()).compareTo(properties.staleThreshold()) > 0;
    }

    public Ticker getLatestTicker(Exchange exchange, MarketType marketType, String symbol) {
        String key = tickerCacheKey(exchange, marketType, symbol);
        Ticker cached = latestTickers.get(key);
        if (cached != null) return cached;
        return tickerMapper.findLatest(exchange.name(), marketType.name(), symbol);
    }

    public List<Kline> getKlines(
            Exchange exchange, MarketType marketType, String symbol, Interval interval, int limit) {
        return klineMapper.findRecent(exchange.name(), marketType.name(), symbol, interval.name(), limit);
    }

    /** 应用关闭时停止所有 worker，避免 Virtual Thread 悬挂到 CF.get 超时。 */
    @PreDestroy
    void shutdown() {
        int n = subscriptions.size();
        subscriptions.values().forEach(state -> state.worker().stop());
        subscriptions.clear();
        log.info("market data service shut down, stopped {} subscriptions", n);
    }

    private static String tickerCacheKey(Exchange ex, MarketType mt, String sym) {
        return ex.name() + ":" + mt.name() + ":" + sym;
    }

    record SubscriptionKey(
            Exchange exchange, MarketType marketType, String symbol, String dataType, Interval interval) {}

    static final class SubscriptionState {
        private final Stoppable worker;
        private final boolean persistent;
        private volatile Instant lastAccess;

        SubscriptionState(Stoppable worker, boolean persistent, Instant now) {
            this.worker = worker;
            this.persistent = persistent;
            this.lastAccess = now;
        }

        void touch() {
            this.lastAccess = Instant.now();
        }

        Stoppable worker() {
            return worker;
        }

        boolean persistent() {
            return persistent;
        }

        Instant lastAccess() {
            return lastAccess;
        }
    }
}
