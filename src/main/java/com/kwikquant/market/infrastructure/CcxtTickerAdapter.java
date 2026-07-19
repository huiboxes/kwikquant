package com.kwikquant.market.infrastructure;

import static com.kwikquant.shared.types.NumberUtils.asBd;
import static com.kwikquant.shared.types.NumberUtils.asLong;

import com.kwikquant.market.domain.Ticker;
import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.MarketType;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * CCXT 标准化 ticker dict({@code Map<String,Object>}) → KwikQuant {@link Ticker} 转换器。
 *
 * <p>CCXT Java 4.5.59 的 {@code watchTicker} 与 {@code fetchTicker} 返回的 raw 都是 CCXT 标准化
 * ticker dict({@code Map},key: symbol/last/bid/ask/high/low/open/baseVolume/quoteVolume/change/
 * percentage/timestamp),对所有交易所一致(见 {@link CcxtTickerWorker} convert 注释 E2E 实测)。
 * 抽成 public static 供 worker(watchTicker stream)+ {@code MarketDataService.fetchTicker}
 * (REST fallback)共用,去重。
 *
 * <p>raw 非 Map 抛 {@link IllegalArgumentException} —— REST fallback 路径视为异常(上抛转
 * {@code ExchangeException});worker 路径调用方 catch 转 log+null(保留原 worker 宽容行为)。
 *
 * @param exchange 交易所(非空)
 * @param marketType 市场类型(非空)
 * @param symbol canonical symbol(如 "BTC/USDT",非空;raw.symbol 缺失时兜底用此)
 */
public final class CcxtTickerAdapter {

    private CcxtTickerAdapter() {}

    public static Ticker toKwikquant(Object raw, Exchange exchange, MarketType marketType, String symbol) {
        Objects.requireNonNull(exchange, "exchange");
        Objects.requireNonNull(marketType, "marketType");
        Objects.requireNonNull(symbol, "symbol");
        if (!(raw instanceof Map<?, ?> m)) {
            throw new IllegalArgumentException(
                    "ccxt ticker raw is not a Map: " + (raw == null ? "null" : raw.getClass().getName()));
        }
        Long ts = asLong(m.get("timestamp"));
        return new Ticker(
                exchange,
                marketType,
                asString(m.get("symbol"), symbol),
                asBd(m.get("last")),
                asBd(m.get("bid")),
                asBd(m.get("ask")),
                asBd(m.get("high")),
                asBd(m.get("low")),
                asBd(m.get("open")),
                asBd(m.get("baseVolume")),
                asBd(m.get("quoteVolume")),
                asBd(m.get("change")),
                asBd(m.get("percentage")),
                ts != null ? Instant.ofEpochMilli(ts) : Instant.now(),
                Instant.now());
    }

    private static String asString(Object o, String fallback) {
        return o != null ? o.toString() : fallback;
    }
}
