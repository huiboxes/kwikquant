package com.kwikquant.market.infrastructure;

import com.kwikquant.market.domain.OrderBook;
import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.MarketType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * CCXT {@code io.github.ccxt.types.OrderBook} → KwikQuant {@link OrderBook} 转换器。
 *
 * <p>CCXT Java 4.5.59 的 {@code fetchOrderBook} 返回 {@code CompletableFuture<Object>}，完成值可能是 raw Map
 * 或已包装的 {@code ccxt.types.OrderBook}。本适配器统一接收 {@code ccxt.types.OrderBook}（由 service 层
 * 用 {@code new OrderBook(raw)} 包装），把 {@code List<List<Double>>} bids/asks 转为 {@link OrderBook.PriceLevel}，
 * 过滤 null/不足 2 元素的档位。
 */
public final class CcxtOrderBookAdapter {

    private CcxtOrderBookAdapter() {}

    public static OrderBook toKwikquant(
            io.github.ccxt.types.OrderBook ob, Exchange exchange, MarketType marketType, String symbol) {
        Objects.requireNonNull(ob, "ccxt orderbook");
        Objects.requireNonNull(exchange);
        Objects.requireNonNull(marketType);
        Objects.requireNonNull(symbol);
        Instant timestamp = ob.timestamp != null ? Instant.ofEpochMilli(ob.timestamp) : null;
        return new OrderBook(
                exchange,
                marketType,
                symbol,
                toPriceLevels(ob.bids, false), // bids 降序(买一=最高价在前)
                toPriceLevels(ob.asks, true), // asks 升序(卖一=最低价在前)
                timestamp,
                Instant.now());
    }

    /**
     * @param isAsk true=asks 升序(卖一=最低价在前),false=bids 降序(买一=最高价在前)。
     *     显式 sort 保证顺序,不依赖 CCXT 返序(防换交易所/版本返非标准顺序导致点差算错)。
     */
    private static List<OrderBook.PriceLevel> toPriceLevels(List<List<Double>> entries, boolean isAsk) {
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        List<OrderBook.PriceLevel> levels = new ArrayList<>(entries.size());
        for (List<Double> entry : entries) {
            if (entry == null || entry.size() < 2) {
                continue;
            }
            Double price = entry.get(0);
            Double amount = entry.get(1);
            if (price == null || amount == null) {
                continue;
            }
            levels.add(new OrderBook.PriceLevel(BigDecimal.valueOf(price), BigDecimal.valueOf(amount)));
        }
        Comparator<OrderBook.PriceLevel> byPrice = Comparator.comparing(OrderBook.PriceLevel::price);
        levels.sort(isAsk ? byPrice : byPrice.reversed());
        return List.copyOf(levels);
    }
}
