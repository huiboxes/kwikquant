package com.kwikquant.market.application;

import static com.kwikquant.shared.types.NumberUtils.asBd;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.kwikquant.market.domain.MarketDataException;
import com.kwikquant.market.domain.TradingPairInfo;
import com.kwikquant.market.infrastructure.CcxtExchangeRegistry;
import com.kwikquant.shared.infra.QuoteCurrencyProperties;
import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.MarketType;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class TradingPairService {

    private static final Logger log = LoggerFactory.getLogger(TradingPairService.class);

    private static final long PAIR_CACHE_TTL_HOURS = 1;
    private static final int PAIR_CACHE_MAX_SIZE = 50;

    private final CcxtExchangeRegistry exchangeRegistry;
    private final QuoteCurrencyProperties quoteCurrencyProperties;

    private final Cache<String, List<TradingPairInfo>> pairCache = Caffeine.newBuilder()
            .expireAfterWrite(PAIR_CACHE_TTL_HOURS, TimeUnit.HOURS)
            .maximumSize(PAIR_CACHE_MAX_SIZE)
            .build();

    public TradingPairService(CcxtExchangeRegistry exchangeRegistry, QuoteCurrencyProperties quoteCurrencyProperties) {
        this.exchangeRegistry = exchangeRegistry;
        this.quoteCurrencyProperties = quoteCurrencyProperties;
    }

    public List<TradingPairInfo> getPairs(Exchange exchange, MarketType marketType) {
        String cacheKey = exchange.name() + ":" + marketType.name();
        return pairCache.get(cacheKey, k -> loadFromCcxt(exchange, marketType));
    }

    /**
     * 经 {@link CcxtExchangeRegistry#marketsOfType} 加载交易对信息(与 canonical 索引共享同一
     * type 过滤——CCXT loadMarkets 一次装 spot+swap+future 全类型,不过滤会让 PERP 命中 SPOT 条目:
     * 规格用现货值 + CCXT 对 OKX spot 恒设 limits.leverage.max=1 → 一切 leverage>1 的 PERP 单被误拒)。
     *
     * <p><b>E2E 已验证</b>（2026-06-29，OKX + Bitget）：CCXT Java 4.5.59+ 的 precision 字段统一返回
     * tick/step 值（Double），不是小数位数。OKX 透传原始 tickSz/lotSz；Bitget 将小数位数转为 10^(-n)。
     * {@code asBd} 直接转 BigDecimal，无需启发式。
     */
    private List<TradingPairInfo> loadFromCcxt(Exchange exchange, MarketType marketType) {
        List<Map<?, ?>> markets;
        try {
            markets = exchangeRegistry.marketsOfType(exchange, marketType);
        } catch (CompletionException e) {
            throw new MarketDataException(
                    "failed loading markets for " + exchange + "." + marketType, e.getCause(), true);
        }

        List<TradingPairInfo> result = new ArrayList<>();
        Set<String> allowed = Set.copyOf(quoteCurrencyProperties.getAllowedCurrencies());
        for (Map<?, ?> m : markets) {
            var info = parseMarket(exchange, marketType, m);
            if (info != null && info.active() && allowed.contains(info.quoteAsset())) {
                result.add(info);
            }
        }
        log.info("loaded {} trading pairs for {}.{}", result.size(), exchange, marketType);
        return result;
    }

    /**
     * CCXT market dict → TradingPairInfo,装载时完成<b>张→币换算</b>:PERP 的 limits.amount(minSz/maxSz)
     * 与 precision.amount(lotSz)是张单位,乘 contractSize(OKX ctVal)币化;precision.price(tickSz)是
     * 价格精度,与合约尺寸无关不换算。symbol 归一为 canonical(base/quote),CCXT unified symbol 另存
     * ccxtSymbol 供边界适配器直取。
     *
     * <p>PERP 缺 contractSize(null/≤0)→ 整条跳过(fail-closed:张币换算语义破坏的 pair 进域内必错钱);
     * SPOT contractSize 恒 1。maxLeverage 仅 PERP 读取——CCXT 对 OKX spot 也无条件设
     * limits.leverage.max(=1),SPOT 读取会把"1"当成真实上限污染语义,恒置 null。
     */
    private static TradingPairInfo parseMarket(Exchange exchange, MarketType marketType, Map<?, ?> m) {
        String ccxtSymbol = asString(m.get("symbol"));
        String base = asString(m.get("base"));
        String quote = asString(m.get("quote"));
        if (ccxtSymbol == null || base == null || quote == null) return null;
        String canonical = base + "/" + quote;
        boolean active = m.get("active") instanceof Boolean b ? b : true;

        BigDecimal contractSize = asBd(m.get("contractSize"));
        if (marketType == MarketType.PERP) {
            if (contractSize == null || contractSize.signum() <= 0) {
                log.warn("PERP market {} has no usable contractSize; skipped (fail-closed)", ccxtSymbol);
                return null;
            }
        } else {
            contractSize = BigDecimal.ONE;
        }

        BigDecimal minQty = null, maxQty = null, tickSize = null, stepSize = null;
        Integer maxLeverage = null;
        if (m.get("limits") instanceof Map<?, ?> limits) {
            if (limits.get("amount") instanceof Map<?, ?> amount) {
                minQty = toCoin(amount.get("min"), contractSize);
                maxQty = toCoin(amount.get("max"), contractSize);
            }
            // CCXT market.limits.leverage.max(整数杠杆上限)。仅 PERP 读取(见方法 javadoc);
            // asBd 转 BigDecimal 再取 int(避免浮点精度问题),<=0 视为未声明置 null。
            if (marketType == MarketType.PERP && limits.get("leverage") instanceof Map<?, ?> leverage) {
                BigDecimal maxLevBd = asBd(leverage.get("max"));
                if (maxLevBd != null) {
                    int ml = maxLevBd.intValue();
                    if (ml > 0) maxLeverage = ml;
                }
            }
        }
        if (m.get("precision") instanceof Map<?, ?> precision) {
            tickSize = asBd(precision.get("price"));
            stepSize = toCoin(precision.get("amount"), contractSize);
        }

        return new TradingPairInfo(
                exchange,
                marketType,
                canonical,
                ccxtSymbol,
                base,
                quote,
                minQty,
                maxQty,
                tickSize,
                stepSize,
                contractSize,
                active,
                maxLeverage);
    }

    /** 张单位原始值 × contractSize → 币单位;原始值 null(交易所未声明)保持 null。 */
    private static BigDecimal toCoin(Object raw, BigDecimal contractSize) {
        BigDecimal v = asBd(raw);
        return v == null ? null : v.multiply(contractSize);
    }

    private static String asString(Object o) {
        return o != null ? o.toString() : null;
    }
}
