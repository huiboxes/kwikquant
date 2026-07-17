package com.kwikquant.market.infrastructure;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class CcxtExchangeRegistry {

    private static final Logger log = LoggerFactory.getLogger(CcxtExchangeRegistry.class);

    private final MarketProperties properties;
    private final ConcurrentHashMap<String, io.github.ccxt.Exchange> exchanges = new ConcurrentHashMap<>();

    public CcxtExchangeRegistry(MarketProperties properties) {
        this.properties = properties;
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

    @PreDestroy
    void shutdown() {
        // CCXT Pro exchange 实例可能持有 WS 连接；当前 CCXT Java 4.5.59 无显式 close()，
        // 清空引用让 GC 回收。watchTicker/watchOHLCV 循环由 worker.stop() 中断退出。
        exchanges.clear();
        log.info("all CCXT exchanges shut down");
    }

    private static io.github.ccxt.Exchange createExchange(Exchange exchange, MarketType mt) {
        String proxyUrl = System.getenv("CCXT_PROXY");
        // CCXT Java: httpsProxy 用于 REST（http:// 格式），wsSocksProxy 用于 WS（socks5:// 格式）
        String httpsProxy = toHttpProxy(proxyUrl);
        String wsSocksProxy = toSocksProxy(proxyUrl);

        io.github.ccxt.Exchange ex = switch (exchange) {
            case BINANCE -> {
                Map<String, Object> config = new HashMap<>();
                if (mt == MarketType.PERP) {
                    config.put("options", Map.of("defaultType", "swap"));
                }
                if (httpsProxy != null) config.put("httpsProxy", httpsProxy);
                yield new Binance(config);
            }
            case OKX -> {
                Map<String, Object> config = new HashMap<>(perpOptions(mt));
                if (httpsProxy != null) config.put("httpsProxy", httpsProxy);
                yield new Okx(config);
            }
            case BITGET -> {
                Map<String, Object> config = new HashMap<>(perpOptions(mt));
                if (httpsProxy != null) config.put("httpsProxy", httpsProxy);
                yield new Bitget(config);
            }
            case PAPER -> throw new IllegalArgumentException("PAPER exchange has no market data");
            default -> throw new IllegalArgumentException("unsupported exchange: " + exchange);
        };

        // WS 代理通过字段直接赋值（config map 可能不被 CCXT Pro WS 客户端读取）
        if (wsSocksProxy != null) {
            ex.wsSocksProxy = wsSocksProxy;
            log.debug("set wsSocksProxy={} for {}", wsSocksProxy, exchange);
        }

        return ex;
    }

    /**
     * 将 CCXT_PROXY 环境变量转为 CCXT Java httpsProxy 格式（http://host:port）。
     * 支持 socks5://、socks5h:// 前缀自动转换；已是 http:// 则原样返回；null/blank 返回 null。
     */
    private static String toHttpProxy(String proxyUrl) {
        if (proxyUrl == null || proxyUrl.isBlank()) {
            return null;
        }
        return proxyUrl.replaceFirst("^socks5h?://", "http://");
    }

    /**
     * 将 CCXT_PROXY 转为 SOCKS5 代理格式（socks5://host:port），供 CCXT Pro WebSocket 使用。
     * 如果输入已是 socks5:// 则原样返回；http:// 转为 socks5://。
     */
    private static String toSocksProxy(String proxyUrl) {
        if (proxyUrl == null || proxyUrl.isBlank()) {
            return null;
        }
        // 确保是 socks5:// 格式
        if (proxyUrl.startsWith("socks5")) {
            return proxyUrl;
        }
        return proxyUrl.replaceFirst("^https?://", "socks5://");
    }

    /** PERP 用 swap defaultType，SPOT 用空 config（CCXT 默认 spot）。 */
    private static Map<String, Object> perpOptions(MarketType mt) {
        return mt == MarketType.PERP ? Map.of("options", Map.of("defaultType", "swap")) : Map.of();
    }

    private static String key(Exchange exchange, MarketType mt) {
        return exchange.name() + "_" + mt.name();
    }
}
