package com.kwikquant.mcp.interfaces.view;

import static com.kwikquant.mcp.interfaces.view.DecimalStrings.str;

import com.kwikquant.market.domain.OrderBook;
import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.MarketType;
import java.time.Instant;
import java.util.List;

/**
 * MCP {@code get_orderbook} 工具返回的盘口投影。{@link PriceLevelView} 嵌套保持 bids/asks 结构；
 * 剥掉 domain 的 {@code receivedAt}（本端抓取时刻）。金额红线：档位价格/数量一律字符串输出。
 */
public record OrderBookView(
        Exchange exchange,
        MarketType marketType,
        String symbol,
        List<PriceLevelView> bids,
        List<PriceLevelView> asks,
        Instant timestamp) {
    public record PriceLevelView(String price, String amount) {
        public static PriceLevelView from(OrderBook.PriceLevel p) {
            return new PriceLevelView(str(p.price()), str(p.qty()));
        }
    }

    public static OrderBookView from(OrderBook ob) {
        return new OrderBookView(
                ob.exchange(),
                ob.marketType(),
                ob.symbol(),
                ob.bids().stream().map(PriceLevelView::from).toList(),
                ob.asks().stream().map(PriceLevelView::from).toList(),
                ob.timestamp());
    }
}
