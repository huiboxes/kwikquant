package com.kwikquant.trading.domain;

import com.kwikquant.market.domain.Kline;
import com.kwikquant.market.domain.Ticker;
import com.kwikquant.shared.types.PriceLevel;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 撮合内核的市场快照输入。统一三种数据源：历史 K 线、实时 ticker、L2 orderbook。
 *
 * <p>字段语义随 fidelity 而异：
 * <ul>
 *   <li>FAST (Backtest): 只用 {@code open/high/low/close}（K 线 OHLC）和 {@code volume}
 *   <li>SPREAD (Paper): 只用 {@code last/bid/ask}（ticker）
 *   <li>DEPTH (Paper): 在 SPREAD 基础上加 {@code bids/asks}（orderbook）
 * </ul>
 *
 * 不适用的字段置 null/空列表。
 */
public record MarketSnapshot(
        Instant timestamp,
        BigDecimal last,
        BigDecimal bid,
        BigDecimal ask,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        BigDecimal volume,
        List<PriceLevel> bids,
        List<PriceLevel> asks) {

    public MarketSnapshot {
        if (bids == null) bids = List.of();
        if (asks == null) asks = List.of();
    }

    /** 从 K 线构造（Backtest 用，FAST fidelity）。 */
    public static MarketSnapshot fromKline(Kline k) {
        return new MarketSnapshot(
                k.openTime(),
                k.close(),
                null,
                null,
                k.open(),
                k.high(),
                k.low(),
                k.close(),
                k.volume(),
                List.of(),
                List.of());
    }

    /** 从 ticker 构造（Paper FAST/SPREAD 用）。 */
    public static MarketSnapshot fromTicker(Ticker t) {
        return new MarketSnapshot(
                t.timestamp(),
                t.last(),
                t.bid(),
                t.ask(),
                t.last(),
                t.last(),
                t.last(),
                t.last(),
                t.baseVolume(),
                List.of(),
                List.of());
    }

    /** 从 ticker + orderbook 构造（Paper DEPTH 用）。 */
    public static MarketSnapshot fromOrderBook(Ticker t, List<PriceLevel> bids, List<PriceLevel> asks) {
        return new MarketSnapshot(
                t.timestamp(),
                t.last(),
                t.bid(),
                t.ask(),
                t.last(),
                t.last(),
                t.last(),
                t.last(),
                t.baseVolume(),
                bids,
                asks);
    }
}
