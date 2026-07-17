package com.kwikquant.market.infrastructure;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class CcxtExchangeRegistry {

    private static final Logger log = LoggerFactory.getLogger(CcxtExchangeRegistry.class);

    private final MarketProperties properties;
    private final ProxyProperties proxyProperties;
    private final ConcurrentHashMap<String, io.github.ccxt.Exchange> exchanges = new ConcurrentHashMap<>();

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
