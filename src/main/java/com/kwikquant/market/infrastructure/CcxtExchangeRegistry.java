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
                log.info("created CCXT exchange instance: {}", key);
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
        return switch (exchange) {
            case BINANCE -> {
                Map<String, Object> config = new HashMap<>();
                if (mt == MarketType.PERP) {
                    config.put("options", Map.of("defaultType", "swap"));
                }
                yield new Binance(config);
            }
            case OKX -> new Okx(perpOptions(mt));
            case BITGET -> new Bitget(perpOptions(mt));
            case PAPER -> throw new IllegalArgumentException("PAPER exchange has no market data");
            default -> throw new IllegalArgumentException("unsupported exchange: " + exchange);
        };
    }

    /** PERP 用 swap defaultType，SPOT 用空 config（CCXT 默认 spot）。 */
    private static Map<String, Object> perpOptions(MarketType mt) {
        return mt == MarketType.PERP ? Map.of("options", Map.of("defaultType", "swap")) : Map.of();
    }

    private static String key(Exchange exchange, MarketType mt) {
        return exchange.name() + "_" + mt.name();
    }
}
