package com.kwikquant.market.application;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.kwikquant.market.domain.MarketDataException;
import com.kwikquant.market.domain.TradingPairInfo;
import com.kwikquant.market.infrastructure.CcxtExchangeRegistry;
import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.MarketType;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class TradingPairService {

    private static final Logger log = LoggerFactory.getLogger(TradingPairService.class);

    private final CcxtExchangeRegistry exchangeRegistry;

    private final Cache<String, List<TradingPairInfo>> pairCache = Caffeine.newBuilder()
            .expireAfterWrite(1, TimeUnit.HOURS)
            .maximumSize(50)
            .build();

    public TradingPairService(CcxtExchangeRegistry exchangeRegistry) {
        this.exchangeRegistry = exchangeRegistry;
    }

    public List<TradingPairInfo> getPairs(Exchange exchange, MarketType marketType) {
        String cacheKey = exchange.name() + ":" + marketType.name();
        return pairCache.get(cacheKey, k -> loadFromCcxt(exchange, marketType));
    }

    /**
     * 从 CCXT loadMarkets 加载交易对信息。
     *
     * <p>设计偏差（见 context.md）：CCXT Java 4.5.59 无 typed Market 类，{@code markets} 是
     * {@code volatile Object}（运行时为 {@code Map<String, Map<String,Object>>}，symbol → 原始 market info map）。
     * {@code loadMarkets()} 返回 {@code CompletableFuture<Object>}，调 {@code .get()} 等待加载完成后读
     * {@code ccxtExchange.markets} 字段。
     *
     * <p><b>E2E 验证点</b>：precision 字段 CCXT 可能是「小数位数」(int) 而非 tick size 值。此处按小数位数处理
     *（{@code 10^(-digits)}）；若 Binance 实测 precision 直接是 tick 值，需改 {@code toTickSize}。标记为
     * wave3 E2E 手动验证项（design §13）。
     */
    @SuppressWarnings("unchecked")
    private List<TradingPairInfo> loadFromCcxt(Exchange exchange, MarketType marketType) {
        var ccxtExchange = exchangeRegistry.getExchange(exchange, marketType);
        try {
            ccxtExchange.loadMarkets(false).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MarketDataException("interrupted loading markets for " + exchange, e, true);
        } catch (ExecutionException e) {
            throw new MarketDataException("failed loading markets for " + exchange, e.getCause(), true);
        }

        Object marketsField = ccxtExchange.markets;
        if (!(marketsField instanceof Map<?, ?> raw)) {
            log.warn("markets field not a Map for {}.{}", exchange, marketType);
            return List.of();
        }

        List<TradingPairInfo> result = new ArrayList<>();
        for (var entry : raw.entrySet()) {
            if (!(entry.getValue() instanceof Map<?, ?> m)) continue;
            var info = parseMarket(exchange, marketType, m);
            if (info != null && info.active()) {
                result.add(info);
            }
        }
        log.info("loaded {} trading pairs for {}.{}", result.size(), exchange, marketType);
        return result;
    }

    @SuppressWarnings("unchecked")
    private static TradingPairInfo parseMarket(Exchange exchange, MarketType marketType, Map<?, ?> m) {
        String symbol = asString(m.get("symbol"));
        String base = asString(m.get("base"));
        String quote = asString(m.get("quote"));
        if (symbol == null) return null;
        Boolean active = m.get("active") instanceof Boolean b ? b : true;

        BigDecimal minQty = null, maxQty = null, tickSize = null, stepSize = null;
        if (m.get("limits") instanceof Map<?, ?> limits) {
            if (limits.get("amount") instanceof Map<?, ?> amount) {
                minQty = asBd(amount.get("min"));
                maxQty = asBd(amount.get("max"));
            }
        }
        if (m.get("precision") instanceof Map<?, ?> precision) {
            tickSize = toTickSize(precision.get("price"));
            stepSize = toTickSize(precision.get("amount"));
        }

        return new TradingPairInfo(
                exchange, marketType, symbol, base, quote, minQty, maxQty, tickSize, stepSize, active);
    }

    /** CCXT precision 可能是小数位数 (Number) 或已是 tick 值 (Number)。按小数位数处理：10^(-digits)。 */
    private static BigDecimal toTickSize(Object precision) {
        if (precision instanceof Number n) {
            double p = n.doubleValue();
            if (p <= 0) return BigDecimal.ZERO;
            // 若 p 是小数位数（如 2 → 0.01），10^(-p)；若 p 已是 tick 值（如 0.01），直接用
            // 启发式：p >= 1 当作小数位数（CCXT 常见），p < 1 当作 tick 值
            if (p >= 1) {
                return BigDecimal.ONE.movePointLeft((int) p);
            }
            return BigDecimal.valueOf(p);
        }
        return null;
    }

    private static String asString(Object o) {
        return o != null ? o.toString() : null;
    }

    private static BigDecimal asBd(Object o) {
        if (o instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        return null;
    }
}
