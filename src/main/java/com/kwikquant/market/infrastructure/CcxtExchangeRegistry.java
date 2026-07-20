package com.kwikquant.market.infrastructure;

import com.kwikquant.market.domain.SymbolNotListedException;
import com.kwikquant.shared.infra.CcxtProxyApplier;
import com.kwikquant.shared.infra.ProxyProperties;
import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.MarketType;
import io.github.ccxt.exchanges.pro.Binance;
import io.github.ccxt.exchanges.pro.Bitget;
import io.github.ccxt.exchanges.pro.Okx;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class CcxtExchangeRegistry {

    private static final Logger log = LoggerFactory.getLogger(CcxtExchangeRegistry.class);

    private final MarketProperties properties;
    private final ProxyProperties proxyProperties;
    private final ConcurrentHashMap<String, io.github.ccxt.Exchange> exchanges = new ConcurrentHashMap<>();

    /** (exchange_marketType) → canonical(base/quote) → CCXT unified symbol 的反向索引,按需构建并缓存。 */
    private final ConcurrentMap<String, Map<String, String>> canonicalMaps = new ConcurrentHashMap<>();

    public CcxtExchangeRegistry(MarketProperties properties, ProxyProperties proxyProperties) {
        this.properties = properties;
        this.proxyProperties = proxyProperties;
    }

    @PostConstruct
    void init() {
        for (var ec : properties.exchanges()) {
            for (var mt : ec.marketTypes()) {
                String key = key(ec.name(), mt);
                io.github.ccxt.Exchange exchange = createExchange(ec.name(), mt);
                exchanges.put(key, exchange);
                log.debug("created CCXT exchange instance: {}", key);
            }
        }
    }

    public io.github.ccxt.Exchange getExchange(Exchange exchange, MarketType marketType) {
        String key = key(exchange, marketType);
        io.github.ccxt.Exchange ex = exchanges.get(key);
        if (ex == null) {
            throw new IllegalArgumentException("exchange not configured: " + key);
        }
        return ex;
    }

    /**
     * 把 canonical symbol(如 {@code BTC/USDT})翻译成该 (交易所, 市场类型) 实例下 CCXT 真正认识的
     * unified symbol。
     *
     * <p>背景:CCXT 的 unified symbol namespace 按市场类型分。spot 实例里 {@code BTC/USDT} 是合法 key;
     * 但 PERP/swap 实例(经 {@code options.defaultType=swap} 构造)里,USDT 本位线性永续的 unified symbol
     * 是 {@code BTC/USDT:USDT}——{@code BTC/USDT} 在该实例的 markets 表里<b>不存在</b>。直接拿 canonical
     * 喂 {@code watchTicker/fetchTicker/createOrder} 会 BadSymbol / WS 订阅永不命中。
     *
     * <p>本方法是 canonical 契约与 CCXT 命名空间之间的<b>唯一翻译端口</b>:
     * market 模块 worker / REST fallback / Wave 8 实盘下单 adapter 调 CCXT 前都应经此翻译。
     * 实现是<b>市场驱动</b>的(读 {@code loadMarkets()} 真实结果反查),不依赖 {@code :USDT} 后缀硬规则,
     * 因此对反向合约({@code BTC/USD:BTC})、COIN-M 等任意形态都正确;canonical={@code base/quote}
     * 天然把线性 vs 反向区分(quote 不同,不会撞)。
     *
     * <p>首次调用某 (exchange, marketType) 时调 {@code loadMarkets().join()} 拉一次 markets(经代理,dev 走
     * .env 代理;prod 走 overrides 直连),构建不可变索引并按 key 缓存;后续 O(1) 查表。loadMarkets 网络
     * 失败则异常透传(不缓存,下次重试);markets 已加载但该 canonical 仍命不到 → 抛
     * {@link SymbolNotListedException}(说明该交易所不挂牌此交易对的此市场类型,fail-fast,不静默死循环)。
     *
     * @param canonical canonical symbol,形如 {@code BTC/USDT}(base/quote,不带合约后缀)
     * @return 该实例下 CCXT 认识的 unified symbol,如 spot→{@code BTC/USDT},perp→{@code BTC/USDT:USDT}
     */
    public String ccxtSymbol(Exchange exchange, MarketType marketType, String canonical) {
        String key = key(exchange, marketType);
        Map<String, String> map = canonicalMaps.computeIfAbsent(key, k -> buildCanonicalMap(exchange, marketType));
        return resolveOrThrow(map, exchange, marketType, canonical);
    }

    /** 在已索引的 canonical→ccxt 表里查 canonical,查不到抛 {@link SymbolNotListedException}。抽成静态纯函数便于单测 not-found 路径(否则需联网 loadMarkets)。 */
    static String resolveOrThrow(
            Map<String, String> canonicalMap, Exchange exchange, MarketType marketType, String canonical) {
        String ccxt = canonicalMap.get(canonical);
        if (ccxt == null) {
            throw new SymbolNotListedException(exchange, marketType, canonical);
        }
        return ccxt;
    }

    /** 按 (exchange, marketType) 拉 markets 并构建 canonical→ccxt 索引。若 {@code marketsLoaded} 已真(如测试注入 markets),跳过 loadMarkets 不联网。纯逻辑抽成 {@link #indexByCanonical} 便于单测。 */
    private Map<String, String> buildCanonicalMap(Exchange exchange, MarketType marketType) {
        io.github.ccxt.Exchange ex = getExchange(exchange, marketType);
        if (!ex.marketsLoaded) {
            // 网络/限频失败抛 CompletionException 透传;computeIfAbsent 不入表,下次调用重试
            ex.loadMarkets().join();
        }
        Map<String, String> indexed = indexByCanonical(ex.markets);
        log.info("indexed {} {} markets: {} canonical symbols", exchange, marketType, indexed.size());
        return indexed;
    }

    /**
     * 把 CCXT {@code exchange.markets}({@code Map<String, Map<String,Object>>} keyed by unified symbol,每个 value 是
     * market dict,含 {@code base/quote/symbol/settle/type/...})反向索引成 {@code canonical(base/quote) → unified symbol}。
     *
     * <p>纯函数,无网络,便于单测。{@code marketsObj} 非 Map 或为空 → 返空表(调用方随后抛
     * {@link SymbolNotListedException})。同一 canonical(base/quote)对应多个 market 时保留首个(我们的范围
     * 内一线性 USDT 永续一个 canonical,无碰撞;若未来扩多合约尺寸等场景,可加选择策略)。
     */
    static Map<String, String> indexByCanonical(Object marketsObj) {
        Map<String, String> out = new HashMap<>();
        if (!(marketsObj instanceof Map<?, ?> raw)) {
            return out;
        }
        for (var entry : raw.entrySet()) {
            if (!(entry.getValue() instanceof Map<?, ?> market)) {
                continue;
            }
            Object base = market.get("base");
            Object quote = market.get("quote");
            Object symbol = market.get("symbol");
            if (base == null || quote == null || symbol == null) {
                continue;
            }
            out.putIfAbsent(base + "/" + quote, symbol.toString());
        }
        return out;
    }

    @PreDestroy
    void shutdown() {
        // CCXT Pro exchange 实例可能持有 WS 连接；当前 CCXT Java 4.5.59 无显式 close()，
        // 清空引用让 GC 回收。watchTicker/watchOHLCV 循环由 worker.stop() 中断退出。
        exchanges.clear();
        log.info("all CCXT exchanges shut down");
    }

    private io.github.ccxt.Exchange createExchange(Exchange exchange, MarketType mt) {
        ProxyProperties.ProxyConfig p = proxyProperties.resolve(exchange);
        Map<String, Object> config = new HashMap<>();
        if (mt == MarketType.PERP) {
            config.put("options", Map.of("defaultType", "swap"));
        }
        CcxtProxyApplier.applyRest(config, p);

        io.github.ccxt.Exchange ex =
                switch (exchange) {
                    case BINANCE -> new Binance(config);
                    case OKX -> new Okx(config);
                    case BITGET -> new Bitget(config);
                    case PAPER -> throw new IllegalArgumentException("PAPER exchange has no market data");
                    default -> throw new IllegalArgumentException("unsupported exchange: " + exchange);
                };

        CcxtProxyApplier.applyWs(ex, p);
        if (p != null && !p.direct() && (p.restProxy() != null || p.wsProxy() != null)) {
            log.debug("applied proxy to {}: rest={} ws={}", exchange, p.restProxy(), p.wsProxy());
        }
        return ex;
    }

    private static String key(Exchange exchange, MarketType mt) {
        return exchange.name() + "_" + mt.name();
    }
}
