package com.kwikquant.market.infrastructure;

import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.MarketType;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "kwikquant.market")
public record MarketProperties(List<ExchangeConfig> exchanges, Duration staleThreshold, Duration idleTimeout) {

    public MarketProperties {
        if (staleThreshold == null) staleThreshold = Duration.ofSeconds(5);
        if (idleTimeout == null) idleTimeout = Duration.ofSeconds(30);
    }

    public record ExchangeConfig(Exchange name, List<MarketType> marketTypes, List<String> persistentSymbols) {

        public ExchangeConfig {
            if (persistentSymbols == null) persistentSymbols = List.of();
        }
    }

    /** 返回 (exchange, marketType) → persistent symbols 的映射 */
    public Map<ExchangeMarketKey, List<String>> persistentSymbols() {
        var result = new HashMap<ExchangeMarketKey, List<String>>();
        for (var ec : exchanges) {
            for (var mt : ec.marketTypes()) {
                if (!ec.persistentSymbols().isEmpty()) {
                    result.put(new ExchangeMarketKey(ec.name(), mt), ec.persistentSymbols());
                }
            }
        }
        return result;
    }

    public record ExchangeMarketKey(Exchange exchange, MarketType marketType) {}
}
