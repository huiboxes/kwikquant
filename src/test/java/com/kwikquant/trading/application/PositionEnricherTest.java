package com.kwikquant.trading.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kwikquant.market.application.MarketDataService;
import com.kwikquant.market.domain.Ticker;
import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.MarginMode;
import com.kwikquant.shared.types.MarketType;
import com.kwikquant.trading.domain.Position;
import com.kwikquant.trading.infrastructure.FundingSettlementMapper;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * {@link PositionEnricher} 单测。验当前市价拉取(持仓主市场类型优先,另一边 fallback)、未实现盈亏
 * 复用 domain {@link Position#getUnrealizedPnl}(含 SHORT 取反)、累计资金费聚合(SPOT 短路返 0
 * 不查 mapper)。
 */
class PositionEnricherTest {

    private final MarketDataService marketDataService = mock(MarketDataService.class);
    private final FundingSettlementMapper fundingSettlementMapper = mock(FundingSettlementMapper.class);
    private final PositionEnricher enricher = new PositionEnricher(marketDataService, fundingSettlementMapper);

    @Test
    void enrich_perpPosition_usesPerpTickerAsPrimary() {
        Position pos = openLong(1L, "BTC/USDT", new BigDecimal("0.5"), new BigDecimal("40000"));
        pos.setMarginMode(MarginMode.ISOLATED);
        // PERP 持仓主查 PERP ticker;SPOT ticker 存在也不该被用(基差偏差高杠杆放大)
        when(marketDataService.getLatestTicker(Exchange.BINANCE, MarketType.PERP, "BTC/USDT"))
                .thenReturn(ticker("50100"));
        when(marketDataService.getLatestTicker(Exchange.BINANCE, MarketType.SPOT, "BTC/USDT"))
                .thenReturn(ticker("50000"));
        when(fundingSettlementMapper.sumFundingAmountByAccountAndSymbol(1L, "BTC/USDT"))
                .thenReturn(new BigDecimal("2.5"));

        PositionEnrichment e = enricher.enrich(pos, Exchange.BINANCE);

        assertThat(e.currentPrice()).isEqualByComparingTo("50100"); // 用 PERP 价,非 SPOT 50000
        assertThat(e.unrealizedPnl()).isEqualByComparingTo("5050"); // (50100-40000)*0.5
        assertThat(e.cumulativeFunding()).isEqualByComparingTo("2.5");
    }

    @Test
    void enrich_perpPosition_perpTickerNull_fallsBackToSpot() {
        Position pos = openLong(1L, "BTC/USDT", new BigDecimal("1"), new BigDecimal("40000"));
        pos.setMarginMode(MarginMode.ISOLATED);
        when(marketDataService.getLatestTicker(Exchange.BINANCE, MarketType.PERP, "BTC/USDT"))
                .thenReturn(null);
        when(marketDataService.getLatestTicker(Exchange.BINANCE, MarketType.SPOT, "BTC/USDT"))
                .thenReturn(ticker("45000"));

        PositionEnrichment e = enricher.enrich(pos, Exchange.BINANCE);

        assertThat(e.currentPrice()).isEqualByComparingTo("45000");
        assertThat(e.unrealizedPnl()).isEqualByComparingTo("5000"); // (45000-40000)*1
    }

    @Test
    void enrich_spotPosition_usesSpotTickerAsPrimary() {
        Position pos = openLong(1L, "BTC/USDT", new BigDecimal("1"), new BigDecimal("40000"));
        // marginMode null (SPOT)
        when(marketDataService.getLatestTicker(Exchange.BINANCE, MarketType.SPOT, "BTC/USDT"))
                .thenReturn(ticker("50000"));
        // PERP ticker 存在也不该被用
        when(marketDataService.getLatestTicker(Exchange.BINANCE, MarketType.PERP, "BTC/USDT"))
                .thenReturn(ticker("99999"));

        PositionEnrichment e = enricher.enrich(pos, Exchange.BINANCE);

        assertThat(e.currentPrice()).isEqualByComparingTo("50000"); // 用 SPOT 价
    }

    @Test
    void enrich_spotTickerNull_fallsBackToPerpTicker() {
        Position pos = openLong(1L, "BTC/USDT", new BigDecimal("1"), new BigDecimal("40000"));
        when(marketDataService.getLatestTicker(Exchange.BINANCE, MarketType.SPOT, "BTC/USDT"))
                .thenReturn(null);
        when(marketDataService.getLatestTicker(Exchange.BINANCE, MarketType.PERP, "BTC/USDT"))
                .thenReturn(ticker("45000"));

        PositionEnrichment e = enricher.enrich(pos, Exchange.BINANCE);

        assertThat(e.currentPrice()).isEqualByComparingTo("45000");
        assertThat(e.unrealizedPnl()).isEqualByComparingTo("5000");
    }

    @Test
    void enrich_noTickerAvailable_returnsNullPriceAndPnl() {
        Position pos = openLong(1L, "BTC/USDT", new BigDecimal("1"), new BigDecimal("40000"));
        when(marketDataService.getLatestTicker(any(), any(), any(String.class))).thenReturn(null);

        PositionEnrichment e = enricher.enrich(pos, Exchange.BINANCE);

        assertThat(e.currentPrice()).isNull();
        assertThat(e.unrealizedPnl()).isNull();
    }

    @Test
    void enrich_spotPosition_cumulativeFundingZeroAndSkipsMapper() {
        Position pos = openLong(1L, "BTC/USDT", new BigDecimal("1"), new BigDecimal("40000"));
        when(marketDataService.getLatestTicker(Exchange.BINANCE, MarketType.SPOT, "BTC/USDT"))
                .thenReturn(ticker("50000"));

        PositionEnrichment e = enricher.enrich(pos, Exchange.BINANCE);

        assertThat(e.cumulativeFunding()).isEqualByComparingTo("0");
        verify(fundingSettlementMapper, never()).sumFundingAmountByAccountAndSymbol(any(Long.class), any());
    }

    @Test
    void enrich_shortPosition_unrealizedPnlNegated() {
        Position pos = Position.flat(1L, "BTC/USDT");
        pos.setSide(Position.SIDE_SHORT);
        pos.setQty(new BigDecimal("1"));
        pos.setAvgEntryPrice(new BigDecimal("40000"));
        when(marketDataService.getLatestTicker(Exchange.BINANCE, MarketType.SPOT, "BTC/USDT"))
                .thenReturn(ticker("38000"));

        PositionEnrichment e = enricher.enrich(pos, Exchange.BINANCE);

        // SHORT: -(38000-40000)*1 = 2000
        assertThat(e.unrealizedPnl()).isEqualByComparingTo("2000");
    }

    private static Position openLong(long accountId, String symbol, BigDecimal qty, BigDecimal avgEntry) {
        Position pos = Position.flat(accountId, symbol);
        pos.setSide(Position.SIDE_LONG);
        pos.setQty(qty);
        pos.setAvgEntryPrice(avgEntry);
        return pos;
    }

    private static Ticker ticker(String last) {
        return new Ticker(
                Exchange.BINANCE,
                MarketType.SPOT,
                "BTC/USDT",
                new BigDecimal(last),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                Instant.parse("2024-01-01T00:00:00Z"),
                Instant.parse("2024-01-01T00:00:00Z"));
    }
}
