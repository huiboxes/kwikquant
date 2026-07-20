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
import com.kwikquant.market.infrastructure.CcxtTickerAdapter;
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

    /** 批量行情端点结果缓存(避免每次 GET /tickers 打交易所,fetchTickers 单请求权重大如 Binance 80)。 */
    private final com.github.benmanes.caffeine.cache.Cache<String, java.util.List<Ticker>> batchTickersCache =
            com.github.benmanes.caffeine.cache.Caffeine.newBuilder()
                    .expireAfterWrite(Duration.ofSeconds(10))
                    .maximumSize(500)
                    .build();

    /** 回测区间 K 线缓存(API-first 拉取结果,2h TTL,避同区间重复打交易所限频)。 */
    private final com.github.benmanes.caffeine.cache.Cache<String, java.util.List<Kline>> rangeCache =
            com.github.benmanes.caffeine.cache.Caffeine.newBuilder()
                    .expireAfterWrite(2, java.util.concurrent.TimeUnit.HOURS)
                    .maximumSize(1000)
                    .build();

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
                    // canonical→CCXT unified symbol 翻译(spot 同形,perp BTC/USDT→BTC/USDT:USDT)。
                    // 命不到(该所不挂牌此交易对的此市场类型)抛 SymbolNotListedException → 上层 catch 记 warn。
                    String ccxtSymbol = exchangeRegistry.ccxtSymbol(exchange, marketType, symbol);
                    var worker = new CcxtTickerWorker(
                            exchangeRegistry.getExchange(exchange, marketType),
                            symbol,
                            ccxtSymbol,
                            this::onTicker,
                            exchange,
                            marketType);
                    worker.start();
                    log.info("subscribed ticker: {}.{}.{} (ccxt={})", exchange, marketType, symbol, ccxtSymbol);
                    return new SubscriptionState(worker, persistent, Instant.now());
                })
                .touch();
    }

    public void subscribeKline(
            Exchange exchange, MarketType marketType, String symbol, Interval interval, boolean persistent) {
        var key = new SubscriptionKey(exchange, marketType, symbol, "kline", interval);
        subscriptions
                .computeIfAbsent(key, k -> {
                    String ccxtSymbol = exchangeRegistry.ccxtSymbol(exchange, marketType, symbol);
                    var worker = new CcxtKlineWorker(
                            exchangeRegistry.getExchange(exchange, marketType),
                            symbol,
                            ccxtSymbol,
                            interval,
                            this::onKline,
                            exchange,
                            marketType);
                    worker.start();
                    log.info(
                            "subscribed kline: {}.{}.{} {} (ccxt={})",
                            exchange,
                            marketType,
                            symbol,
                            interval,
                            ccxtSymbol);
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
    public void unsubscribeKline(Exchange exchange, MarketType marketType, String symbol, Interval interval) {
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
        return getKlines(exchange, marketType, symbol, interval, limit, null);
    }

    /**
     * 往前滚加载历史:{@code before != null} 时优先 DB {@code findBefore} 拉 {@code open_time < before} 的最近 N 根,
     * 不足(非 persistent interval 无 worker 抓)→ CCXT {@code fetchOHLCV since = before - limit*interval} 分页
     * (正经交易所分页做法,往前推 limit 根,返 [since, before) 的历史)。前端 sort ASC + prepend 到现有 data 前。
     * {@code before=null} 同现状(最近 N 根)。
     */
    public List<Kline> getKlines(
            Exchange exchange, MarketType marketType, String symbol, Interval interval, int limit, Instant before) {
        if (before != null) {
            List<Kline> older = klineMapper.findBefore(
                    exchange.name(), marketType.name(), symbol, interval.ccxtValue(), before, limit);
            if (older != null && older.size() >= limit) return older;
            // DB 不足 → CCXT since 分页:before - limit*intervalMs 往前推,fetchOHLCV 返 before 之前的历史。
            long since = before.toEpochMilli() - (long) limit * interval.toMillis();
            return fetchKlines(exchange, marketType, symbol, interval, limit, since);
        }
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
     * 按时间范围批量查询历史 K 线(回测 + MCP 区间查询用)。委托 {@link #fetchKlineRangeApiFirst}
     * (API-first + Caffeine 缓存),不再读 klines 表 —— 回测按需拉交易所历史,避免 DB-first 在数据缺失时
     * 静默返空(模拟盘 OKX 账户查 Binance klines 0 行的根因)。这是 market 模块向 trading/mcp 暴露的
     * 应用层查询入口,保持模块边界清晰(trading 不直接依赖 {@code market.infrastructure.KlineMapper})。
     *
     * <p><b>语义变更(破坏性)</b>:endTime 从旧 {@code KlineMapper.findRange} 的 inclusive
     * ({@code open_time <= endTime})改为 exclusive({@code open_time < end}),与 fetchKlineRangeApiFirst
     * 区间分页过滤一致。MCP 及其他调用方需注意 end 那一根 K 线不再包含。
     */
    public List<Kline> getKlineRange(
            Exchange exchange,
            MarketType marketType,
            String symbol,
            Interval interval,
            Instant startTime,
            Instant endTime) {
        return fetchKlineRangeApiFirst(exchange, marketType, symbol, interval, startTime, endTime);
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
        String ccxtSymbol = exchangeRegistry.ccxtSymbol(exchange, marketType, symbol);
        try {
            Object raw = ccxt.fetchOrderBook(ccxtSymbol, limit).join();
            io.github.ccxt.types.OrderBook ob =
                    raw instanceof io.github.ccxt.types.OrderBook o ? o : new io.github.ccxt.types.OrderBook(raw);
            return CcxtOrderBookAdapter.toKwikquant(ob, exchange, marketType, symbol);
        } catch (CompletionException e) {
            throw new ExchangeException(
                    "fetchOrderBook failed for " + symbol + ": " + describeCause(e), e.getCause(), true);
        }
    }

    /**
     * 抓取实时单 symbol ticker 快照(REST fallback 用:非 persistent symbol 无 worker 持续推时拉单次快照)。
     * 走 CCXT {@code fetchTicker} 同步阻塞,不持久化(快照瞬态,无存档价值,且避免污染 DB/latestTickers 缓存)。
     * 不写 latestTickers 内存缓存(fallback 快照不应伪装成 persistent fresh;stale 由调用方据"无 worker
     * 持续推"判 true)。异常语义同 {@link #fetchOrderBook}。
     *
     * @return CCXT 标准化 ticker dict 转的 domain Ticker(receivedAt=now)
     */
    public Ticker fetchTicker(Exchange exchange, MarketType marketType, String symbol) {
        io.github.ccxt.Exchange ccxt = exchangeRegistry.getExchange(exchange, marketType);
        String ccxtSymbol = exchangeRegistry.ccxtSymbol(exchange, marketType, symbol);
        try {
            Object raw = ccxt.fetchTicker(ccxtSymbol).join();
            return CcxtTickerAdapter.toKwikquant(raw, exchange, marketType, symbol);
        } catch (CompletionException e) {
            throw new ExchangeException(
                    "fetchTicker failed for " + symbol + ": " + describeCause(e), e.getCause(), true);
        }
    }

    /**
     * 批量行情快照(REST {@code GET /tickers} 用)。走 CCXT {@code fetchTickers(symbols)} 1 次调用替 N 次
     * {@code fetchTicker},sort/limit/search 应用层做,Caffeine 10s 缓存摊薄单请求权重(如 Binance /tickers
     * weight 80)。{@code symbols} 由调用方(controller 从 {@code TradingPairService.getPairs} 拿 canonical
     * 全量)传入;service 内 {@code exchangeRegistry.ccxtSymbol} 翻译成 unified 喂 CCXT,返回 domain
     * Ticker.symbol 仍是 canonical(不泄 :USDT 后缀)。stale 不在此返(controller 全标 false,batch 快照语义)。
     *
     * @param symbols canonical symbol 全量列表(如 ["BTC/USDT","ETH/USDT",...]),由 controller 传
     * @param sort quoteVolume(默认)|percentage|last
     * @param order desc(默认)|asc
     * @param limit 调用方已 clamp 到 1-500,service 再兜底
     * @param search null/空=不过滤,否则按 canonical symbol like 过滤
     */
    public java.util.List<Ticker> fetchTickers(
            Exchange exchange,
            MarketType marketType,
            java.util.List<String> symbols,
            String sort,
            String order,
            int limit,
            String search) {
        int safeLimit = Math.min(Math.max(limit, 1), 500);
        String cacheKey = exchange.name() + ":" + marketType.name() + ":" + sort + ":" + order + ":" + safeLimit + ":"
                + (search == null ? "" : search) + ":" + symbols.hashCode();
        return batchTickersCache.get(
                cacheKey, k -> loadTickers(exchange, marketType, symbols, sort, order, safeLimit, search));
    }

    private java.util.List<Ticker> loadTickers(
            Exchange exchange,
            MarketType marketType,
            java.util.List<String> symbols,
            String sort,
            String order,
            int limit,
            String search) {
        if (symbols.isEmpty()) return java.util.List.of();

        // canonical → ccxt unified + 反查表(fetchTickers 返回的 key 是 unified,要翻回 canonical 喂 adapter)
        java.util.Map<String, String> ccxtToCanonical = new java.util.HashMap<>();
        java.util.List<String> ccxtSymbols = new java.util.ArrayList<>();
        String lowerSearch = (search == null || search.isBlank()) ? null : search.toLowerCase();
        for (String canonical : symbols) {
            if (canonical == null) continue;
            if (lowerSearch != null && !canonical.toLowerCase().contains(lowerSearch)) continue;
            try {
                String ccxtSymbol = exchangeRegistry.ccxtSymbol(exchange, marketType, canonical);
                ccxtSymbols.add(ccxtSymbol);
                ccxtToCanonical.put(ccxtSymbol, canonical);
            } catch (RuntimeException e) {
                log.debug("skip canonical {} for {}.{}: {}", canonical, exchange, marketType, e.getMessage());
            }
        }
        if (ccxtSymbols.isEmpty()) return java.util.List.of();

        io.github.ccxt.Exchange ccxt = exchangeRegistry.getExchange(exchange, marketType);
        Object raw;
        try {
            raw = ccxt.fetchTickers(ccxtSymbols).join();
        } catch (CompletionException e) {
            throw new ExchangeException(
                    "fetchTickers failed for " + exchange + ": " + describeCause(e), e.getCause(), true);
        }
        if (!(raw instanceof java.util.Map<?, ?> rawMap)) {
            log.warn("fetchTickers returned non-Map for {}.{}", exchange, marketType);
            return java.util.List.of();
        }

        java.util.List<Ticker> tickers = new java.util.ArrayList<>();
        for (var entry : rawMap.entrySet()) {
            if (!(entry.getValue() instanceof java.util.Map<?, ?> tickMap)) continue;
            String ccxtKey = entry.getKey() != null ? entry.getKey().toString() : null;
            if (ccxtKey == null) continue;
            String canonical = ccxtToCanonical.get(ccxtKey);
            if (canonical == null) {
                log.debug("fetchTickers returned unknown symbol {}, skipping", ccxtKey);
                continue;
            }
            try {
                tickers.add(CcxtTickerAdapter.toKwikquant(tickMap, exchange, marketType, canonical));
            } catch (RuntimeException e) {
                log.debug("skip ticker {}: {}", ccxtKey, e.getMessage());
            }
        }

        java.util.Comparator<Ticker> desc = comparatorFor(sort);
        java.util.Comparator<Ticker> cmp = "asc".equalsIgnoreCase(order) ? desc.reversed() : desc;
        tickers.sort(cmp);
        if (tickers.size() > limit) tickers = tickers.subList(0, limit);
        return tickers;
    }

    /** 排序 comparator(降序 base,大→小);order=asc 时 caller 整体 reversed。null 字段兜底 ZERO 避 NPE。 */
    private static java.util.Comparator<Ticker> comparatorFor(String sort) {
        if ("percentage".equalsIgnoreCase(sort)) {
            return java.util.Comparator.comparing(
                    t -> t.percentage() != null ? t.percentage() : java.math.BigDecimal.ZERO,
                    java.util.Comparator.reverseOrder());
        }
        if ("last".equalsIgnoreCase(sort)) {
            return java.util.Comparator.comparing(
                    t -> t.last() != null ? t.last() : java.math.BigDecimal.ZERO, java.util.Comparator.reverseOrder());
        }
        // 默认 quoteVolume(成交额)
        return java.util.Comparator.comparing(
                t -> t.quoteVolume() != null ? t.quoteVolume() : java.math.BigDecimal.ZERO,
                java.util.Comparator.reverseOrder());
    }

    /**
     * 抓取当前资金费率（Wave 10 MCP {@code get_funding_rate} 用，仅 PERP）。走 CCXT {@code fetchFundingRate}
     * 同步阻塞，不持久化。异常语义同 {@link #fetchOrderBook}。
     */
    public FundingRate fetchFundingRate(Exchange exchange, MarketType marketType, String symbol) {
        io.github.ccxt.Exchange ccxt = exchangeRegistry.getExchange(exchange, marketType);
        String ccxtSymbol = exchangeRegistry.ccxtSymbol(exchange, marketType, symbol);
        try {
            Object raw = ccxt.fetchFundingRate(ccxtSymbol).join();
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
        return fetchKlines(exchange, marketType, symbol, interval, limit, null);
    }

    /**
     * 抓取历史 K 线(REST {@code /klines} fallback + before 分页用)。走 CCXT {@code fetchOHLCV} 同步阻塞,
     * 不持久化(历史按需拉,先不 upsert DB 缓存,后续可加避限频)。{@code sinceMs != null} 时按 since
     * 分页(正经交易所分页做法,before 历史点往前推 limit 根)。PAPER/未配置 exchange 由
     * {@link CcxtExchangeRegistry#getExchange} 抛 IllegalArgumentException;CCXT 限频/网络失败抛
     * {@link ExchangeException}。异常语义同 {@link #fetchOrderBook}。
     *
     * @param limit 返回条数,调用方保证 > 0
     * @param sinceMs CCXT since 毫秒戳,null = 最近 N 根(无 since)
     */
    public List<Kline> fetchKlines(
            Exchange exchange, MarketType marketType, String symbol, Interval interval, int limit, Long sinceMs) {
        io.github.ccxt.Exchange ccxt = exchangeRegistry.getExchange(exchange, marketType);
        String ccxtSymbol = exchangeRegistry.ccxtSymbol(exchange, marketType, symbol);
        try {
            Object raw = ccxt.fetchOHLCV(ccxtSymbol, interval.ccxtValue(), sinceMs, limit)
                    .join();
            return CcxtKlineAdapter.toKwikquant(raw, exchange, marketType, symbol, interval);
        } catch (CompletionException e) {
            throw new ExchangeException(
                    "fetchOHLCV failed for " + symbol + " " + interval + ": " + describeCause(e), e.getCause(), true);
        }
    }

    /**
     * 按时间区间拉历史 K 线(API-first + Caffeine 缓存,回测数据获取用)。走 CCXT {@code fetchOHLCV} since
     * 分页(从 {@code start} 起,每次 1000 根,{@code since} 推进 = 上页最后一根 openTime + intervalMs,直到
     * 覆盖 {@code end} 或交易所返空页),不查不写 klines 表(回测按需拉,避免污染实时落库)。同参数 2h 内命中
     * 缓存不打交易所。CCXT 限频/网络失败抛 {@link ExchangeException}(语义同 {@link #fetchKlines})。
     * 交易所返空 → 返空 list(上层据空结果 {@code markFailed},不在此抛)。
     *
     * @param start 区间起点(含)
     * @param end 区间终点(不含;{@code open_time >= end} 的被过滤)
     */
    public List<Kline> fetchKlineRangeApiFirst(
            Exchange exchange, MarketType marketType, String symbol, Interval interval, Instant start, Instant end) {
        String key = exchange.name() + "|" + marketType.name() + "|" + symbol + "|" + interval.ccxtValue() + "|" + start
                + "|" + end;
        return rangeCache.get(key, k -> fetchRangeFromApi(exchange, marketType, symbol, interval, start, end));
    }

    private List<Kline> fetchRangeFromApi(
            Exchange exchange, MarketType marketType, String symbol, Interval interval, Instant start, Instant end) {
        io.github.ccxt.Exchange ccxt = exchangeRegistry.getExchange(exchange, marketType);
        String ccxtSymbol = exchangeRegistry.ccxtSymbol(exchange, marketType, symbol);
        long intervalMs = interval.toMillis();
        long endMs = end.toEpochMilli();
        java.util.List<Kline> acc = new java.util.ArrayList<>();
        long since = start.toEpochMilli();
        try {
            while (since < endMs) {
                Object raw = ccxt.fetchOHLCV(ccxtSymbol, interval.ccxtValue(), since, 1000)
                        .join();
                java.util.List<Kline> page = CcxtKlineAdapter.toKwikquant(raw, exchange, marketType, symbol, interval);
                if (page.isEmpty()) break;
                acc.addAll(page);
                long lastOpen = page.get(page.size() - 1).openTime().toEpochMilli();
                long next = lastOpen + intervalMs;
                if (next <= since) break; // 防无限循环(交易所异常返旧数据,since 不推进)
                since = next;
            }
        } catch (CompletionException e) {
            throw new ExchangeException(
                    "fetchOHLCV range failed for " + symbol + " " + interval + ": " + describeCause(e),
                    e.getCause(),
                    true);
        }
        // 过滤 open_time >= end(交易所最后一页可能越过区间右端)
        return acc.stream().filter(k -> k.openTime().isBefore(end)).toList();
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
