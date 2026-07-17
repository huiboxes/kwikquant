package com.kwikquant.market.application;

import com.kwikquant.market.domain.FundingRate;
import com.kwikquant.market.domain.Kline;
import com.kwikquant.market.domain.OrderBook;
import com.kwikquant.market.domain.Ticker;
import com.kwikquant.market.infrastructure.CcxtExchangeRegistry;
import com.kwikquant.market.infrastructure.CcxtFundingRateAdapter;
import com.kwikquant.market.infrastructure.CcxtKlineAdapter;
import com.kwikquant.market.infrastructure.CcxtKlineWorker;
import com.kwikquant.market.infrastructure.CcxtOrderBookAdapter;
import com.kwikquant.market.infrastructure.CcxtTickerWorker;
import com.kwikquant.market.infrastructure.KlineMapper;
import com.kwikquant.market.infrastructure.MarketProperties;
import com.kwikquant.market.infrastructure.Stoppable;
import com.kwikquant.market.infrastructure.TickerMapper;
import com.kwikquant.shared.infra.ExchangeException;
import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.Interval;
import com.kwikquant.shared.types.MarketType;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
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

    /** 持久化订阅（{@code onApplicationReady} 播种）统一抓取的 K 线周期。 */
    private static final Interval PERSISTENT_KLINE_INTERVAL = Interval._1m;

    /** 空闲订阅清理轮询间隔（毫秒）。 */
    private static final long IDLE_CLEANUP_INTERVAL_MS = 10_000;

    private static final String TICKER_TOPIC_FORMAT = "/topic/ticker/%s/%s/%s";
    private static final String KLINE_TOPIC_FORMAT = "/topic/kline/%s/%s/%s/%s";

    private final CcxtExchangeRegistry exchangeRegistry;
    private final SimpMessagingTemplate messagingTemplate;
    private final KlineMapper klineMapper;
    private final TickerMapper tickerMapper;
    private final MarketProperties properties;

    private final ConcurrentMap<SubscriptionKey, SubscriptionState> subscriptions = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Ticker> latestTickers = new ConcurrentHashMap<>();
    private final List<Consumer<Ticker>> tickerListeners = new CopyOnWriteArrayList<>();

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
                    subscribeKline(exchange, marketType, symbol, PERSISTENT_KLINE_INTERVAL, true);
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

    /** 退订 ticker(按 symbol,不影响同 symbol 的 kline 订阅,后者用 {@link #unsubscribeKline})。 */
    public void unsubscribe(Exchange exchange, MarketType marketType, String symbol) {
        subscriptions.entrySet().removeIf(entry -> {
            var k = entry.getKey();
            if (k.exchange() == exchange
                    && k.marketType() == marketType
                    && k.symbol().equals(symbol)
                    && "ticker".equals(k.dataType())
                    && !entry.getValue().persistent()) {
                entry.getValue().worker().stop();
                log.info("unsubscribed: {}.{}.{}", exchange, marketType, symbol);
                return true;
            }
            return false;
        });
    }

    /** 按 (exchange, marketType, symbol, interval) 退订 kline(不影响同 symbol 的 ticker)。 */
    public void unsubscribeKline(
            Exchange exchange, MarketType marketType, String symbol, Interval interval) {
        subscriptions.entrySet().removeIf(entry -> {
            var k = entry.getKey();
            if (k.exchange() == exchange
                    && k.marketType() == marketType
                    && k.symbol().equals(symbol)
                    && "kline".equals(k.dataType())
                    && interval.equals(k.interval())
                    && !entry.getValue().persistent()) {
                entry.getValue().worker().stop();
                log.info("unsubscribed kline: {}.{}.{} {}", exchange, marketType, symbol, interval);
                return true;
            }
            return false;
        });
    }

    @Scheduled(fixedDelay = IDLE_CLEANUP_INTERVAL_MS)
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
                TICKER_TOPIC_FORMAT,
                ticker.exchange(),
                ticker.marketType(),
                ticker.symbol().replace("/", "-"));
        messagingTemplate.convertAndSend(destination, ticker);

        tickerMapper.upsert(TickerMapper.TickerRow.from(ticker));

        // 通知所有订阅者（PaperExecutor 等内部消费者）。单个 listener 异常不影响其他。
        for (Consumer<Ticker> listener : tickerListeners) {
            try {
                listener.accept(ticker);
            } catch (RuntimeException e) {
                log.warn("ticker listener threw: {}", e.getMessage());
            }
        }
    }

    /** 注册 ticker 监听器（用于 PaperExecutor 等内部消费者）。 */
    public void addTickerListener(Consumer<Ticker> listener) {
        tickerListeners.add(listener);
    }

    void onKline(Kline kline) {
        String destination = String.format(
                KLINE_TOPIC_FORMAT,
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
        List<Kline> cached =
                klineMapper.findRecent(exchange.name(), marketType.name(), symbol, interval.ccxtValue(), limit);
        // DB 不足(空或 < limit,如非 persistent interval 无 worker 抓)→ fallback CCXT fetchOHLCV 拉历史,
        // 保证任意 interval 都有数据。本 plan 先不 upsert DB 缓存(后续可加避限频)。
        if (cached != null && cached.size() >= limit) {
            return cached;
        }
        return fetchKlines(exchange, marketType, symbol, interval, limit);
    }

    /**
     * 按时间范围批量查询历史 K 线（BacktestExecutor 用）。
     *
     * <p>这是 market 模块向 trading 模块暴露的应用层查询入口 —— trading 不应直接依赖
     * {@code market.infrastructure.KlineMapper}，统一经由本方法访问，保持模块边界清晰。
     */
    public List<Kline> getKlineRange(
            Exchange exchange,
            MarketType marketType,
            String symbol,
            Interval interval,
            Instant startTime,
            Instant endTime) {
        return klineMapper.findRange(
                exchange.name(), marketType.name(), symbol, interval.ccxtValue(), startTime, endTime);
    }

    /**
     * 抓取实时盘口深度（Wave 10 MCP {@code get_orderbook} 用）。走 CCXT {@code fetchOrderBook} 同步阻塞，
     * 不持久化（盘口瞬态，无存档价值）。PAPER/未配置 exchange 由 {@link CcxtExchangeRegistry#getExchange}
     * 抛 IllegalArgumentException（调用方按需 catch 转 MCP 10002）；CCXT 限频/网络失败抛 {@link ExchangeException}。
     *
     * @param limit 档位数，调用方保证 > 0
     */
    public OrderBook fetchOrderBook(Exchange exchange, MarketType marketType, String symbol, int limit) {
        io.github.ccxt.Exchange ccxt = exchangeRegistry.getExchange(exchange, marketType);
        try {
            Object raw = ccxt.fetchOrderBook(symbol, limit).join();
            io.github.ccxt.types.OrderBook ob =
                    raw instanceof io.github.ccxt.types.OrderBook o ? o : new io.github.ccxt.types.OrderBook(raw);
            return CcxtOrderBookAdapter.toKwikquant(ob, exchange, marketType, symbol);
        } catch (CompletionException e) {
            throw new ExchangeException(
                    "fetchOrderBook failed for " + symbol + ": " + describeCause(e), e.getCause(), true);
        }
    }

    /**
     * 抓取当前资金费率（Wave 10 MCP {@code get_funding_rate} 用，仅 PERP）。走 CCXT {@code fetchFundingRate}
     * 同步阻塞，不持久化。异常语义同 {@link #fetchOrderBook}。
     */
    public FundingRate fetchFundingRate(Exchange exchange, MarketType marketType, String symbol) {
        io.github.ccxt.Exchange ccxt = exchangeRegistry.getExchange(exchange, marketType);
        try {
            Object raw = ccxt.fetchFundingRate(symbol).join();
            io.github.ccxt.types.FundingRate fr =
                    raw instanceof io.github.ccxt.types.FundingRate f ? f : new io.github.ccxt.types.FundingRate(raw);
            return CcxtFundingRateAdapter.toKwikquant(fr, exchange, marketType, symbol);
        } catch (CompletionException e) {
            throw new ExchangeException(
                    "fetchFundingRate failed for " + symbol + ": " + describeCause(e), e.getCause(), true);
        }
    }

    /**
     * 抓取历史 K 线(REST {@code /klines} fallback 用)。走 CCXT {@code fetchOHLCV} 同步阻塞,不持久化
     * (历史按需拉,先不 upsert DB 缓存,后续可加避限频)。PAPER/未配置 exchange 由
     * {@link CcxtExchangeRegistry#getExchange} 抛 IllegalArgumentException;CCXT 限频/网络失败抛
     * {@link ExchangeException}。异常语义同 {@link #fetchOrderBook}。
     *
     * @param limit 返回条数,调用方保证 > 0
     */
    public List<Kline> fetchKlines(
            Exchange exchange, MarketType marketType, String symbol, Interval interval, int limit) {
        io.github.ccxt.Exchange ccxt = exchangeRegistry.getExchange(exchange, marketType);
        try {
            Object raw = ccxt.fetchOHLCV(symbol, interval.ccxtValue(), null, limit).join();
            return CcxtKlineAdapter.toKwikquant(raw, exchange, marketType, symbol, interval);
        } catch (CompletionException e) {
            throw new ExchangeException(
                    "fetchOHLCV failed for " + symbol + " " + interval + ": " + describeCause(e),
                    e.getCause(),
                    true);
        }
    }

    private static String describeCause(CompletionException e) {
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        String msg = cause.getMessage();
        return msg != null ? msg : cause.getClass().getSimpleName();
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
