package com.kwikquant.market.infrastructure;

import static com.kwikquant.shared.types.NumberUtils.asBd;
import static com.kwikquant.shared.types.NumberUtils.asLong;

import com.kwikquant.market.domain.Kline;
import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.Interval;
import com.kwikquant.shared.types.MarketType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * CCXT {@code fetchOHLCV} 原始返回 → KwikQuant {@link Kline} 转换器。
 *
 * <p>CCXT Java 的 {@code fetchOHLCV(symbol, timeframe, since, limit)} 返回
 * {@code CompletableFuture<Object>}，完成值是 {@code List<List<Object>>}（每根 candle 是<b>位置数组</b>
 * {@code [timestamp, open, high, low, close, volume]}，不是 typed OHLCV，与 {@code watchOHLCV} 一致）。
 * 本适配器按位置读字段（0=ts, 1=open, 2=high, 3=low, 4=close, 5=volume），逻辑抽自
 * {@code CcxtKlineWorker.convertLastCandle}（那里只取最后一根，这里转全部，供 REST 历史拉取 fallback 用）。
 *
 * <p>过滤 null / 不足 6 元素 / 缺 timestamp 的 candle，跳过不抛（容错）。
 */
public final class CcxtKlineAdapter {

    private CcxtKlineAdapter() {}

    /**
     * @param ohlcv      CCXT fetchOHLCV 原始返回（运行时 {@code List<List<Object>>}，每根 candle 位置数组）
     * @param exchange   交易所
     * @param marketType 市场类型
     * @param symbol     canonical symbol（如 "BTC/USDT"，带斜杠）
     * @param interval   K 线周期
     * @return 按 ohlcv 顺序的 Kline 列表（空/非 List 输入返空 list；非法 candle 跳过）
     */
    public static List<Kline> toKwikquant(
            Object ohlcv, Exchange exchange, MarketType marketType, String symbol, Interval interval) {
        Objects.requireNonNull(exchange, "exchange");
        Objects.requireNonNull(marketType, "marketType");
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(interval, "interval");
        if (!(ohlcv instanceof List<?> candles) || candles.isEmpty()) {
            return List.of();
        }
        List<Kline> result = new ArrayList<>(candles.size());
        for (Object c : candles) {
            if (!(c instanceof List<?> candle) || candle.size() < 6) {
                continue;
            }
            Long ts = asLong(candle.get(0));
            if (ts == null) {
                continue;
            }
            Kline kline = new Kline(
                    exchange,
                    marketType,
                    symbol,
                    interval,
                    Instant.ofEpochMilli(ts),
                    asBd(candle.get(1)), // open
                    asBd(candle.get(2)), // high
                    asBd(candle.get(3)), // low
                    asBd(candle.get(4)), // close
                    asBd(candle.get(5))); // volume
            result.add(kline);
        }
        return List.copyOf(result);
    }
}
