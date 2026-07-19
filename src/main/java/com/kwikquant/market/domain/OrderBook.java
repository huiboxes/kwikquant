package com.kwikquant.market.domain;

import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.MarketType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 盘口深度快照。bids/asks 按 {@link PriceLevel} 列出；CCXT 返回的 {@code List<List<Double>} 已由
 * {@code CcxtOrderBookAdapter} 转为 {@link BigDecimal}（价格、数量）。timestamp 可能为 null（部分交易所不返），
 * receivedAt 为本端抓取时刻，恒非 null。
 */
public record OrderBook(
        Exchange exchange,
        MarketType marketType,
        String symbol,
        List<PriceLevel> bids,
        List<PriceLevel> asks,
        Instant timestamp,
        Instant receivedAt) {

    /** 单档价位：price + qty（Base 资产数量）。字段名 qty 与 shared.types.PriceLevel + 前端契约对齐。 */
    public record PriceLevel(BigDecimal price, BigDecimal qty) {
        public PriceLevel {
            Objects.requireNonNull(price);
            Objects.requireNonNull(qty);
        }
    }

    public OrderBook {
        Objects.requireNonNull(exchange);
        Objects.requireNonNull(marketType);
        Objects.requireNonNull(symbol);
        Objects.requireNonNull(bids);
        Objects.requireNonNull(asks);
        Objects.requireNonNull(receivedAt);
    }
}
