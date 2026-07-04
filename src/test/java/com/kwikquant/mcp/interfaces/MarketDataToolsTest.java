package com.kwikquant.mcp.interfaces;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kwikquant.market.application.MarketDataService;
import com.kwikquant.market.domain.FundingRate;
import com.kwikquant.market.domain.Kline;
import com.kwikquant.market.domain.OrderBook;
import com.kwikquant.market.domain.Ticker;
import com.kwikquant.mcp.interfaces.view.FundingRateView;
import com.kwikquant.mcp.interfaces.view.KlineView;
import com.kwikquant.mcp.interfaces.view.OrderBookView;
import com.kwikquant.mcp.interfaces.view.TickerView;
import com.kwikquant.shared.infra.McpToolParamInvalidException;
import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.Interval;
import com.kwikquant.shared.types.MarketType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link MarketDataTools} 单测。验证：4 个 @McpTool 方法的参数解析（String→枚举/Instant，非法抛 10002）、
 * service 委托、View 投影、SPOT 调 funding_rate 抛 10002、PAPER 由 service IllegalArgumentException 转 10002。
 */
class MarketDataToolsTest {

    private MarketDataService service;
    private MarketDataTools tools;

    @BeforeEach
    void setUp() {
        service = mock(MarketDataService.class);
        tools = new MarketDataTools(service);
    }

    // ── get_ohlcv ──

    @Test
    void getOhlcv_validParams_shouldDelegateToGetKlineRangeAndProject() {
        Instant start = Instant.parse("2024-01-01T00:00:00Z");
        Instant end = Instant.parse("2024-02-01T00:00:00Z");
        Kline k = new Kline(
                Exchange.BINANCE,
                MarketType.SPOT,
                "BTC/USDT",
                Interval._1h,
                start,
                BigDecimal.valueOf(50000),
                BigDecimal.valueOf(50100),
                BigDecimal.valueOf(49900),
                BigDecimal.valueOf(50050),
                BigDecimal.valueOf(12.5));
        when(service.getKlineRange(Exchange.BINANCE, MarketType.SPOT, "BTC/USDT", Interval._1h, start, end))
                .thenReturn(List.of(k));

        List<KlineView> result =
                tools.getOhlcv("binance", "spot", "BTC/USDT", "1h", "2024-01-01T00:00:00Z", "2024-02-01T00:00:00Z");

        assertThat(result).hasSize(1);
        KlineView v = result.get(0);
        assertThat(v.exchange()).isEqualTo(Exchange.BINANCE);
        assertThat(v.interval()).isEqualTo(Interval._1h);
        assertThat(v.close()).isEqualByComparingTo("50050");
        verify(service).getKlineRange(Exchange.BINANCE, MarketType.SPOT, "BTC/USDT", Interval._1h, start, end);
    }

    @Test
    void getOhlcv_invalidExchange_shouldThrow10002() {
        assertThatThrownBy(() -> tools.getOhlcv(
                        "coinbase", "spot", "BTC/USDT", "1h", "2024-01-01T00:00:00Z", "2024-02-01T00:00:00Z"))
                .isInstanceOf(McpToolParamInvalidException.class)
                .hasMessageContaining("exchange");
    }

    @Test
    void getOhlcv_invalidInterval_shouldThrow10002() {
        assertThatThrownBy(() -> tools.getOhlcv(
                        "binance", "spot", "BTC/USDT", "2h", "2024-01-01T00:00:00Z", "2024-02-01T00:00:00Z"))
                .isInstanceOf(McpToolParamInvalidException.class)
                .hasMessageContaining("interval");
    }

    @Test
    void getOhlcv_invalidStartTimestamp_shouldThrow10002() {
        assertThatThrownBy(
                        () -> tools.getOhlcv("binance", "spot", "BTC/USDT", "1h", "not-a-date", "2024-02-01T00:00:00Z"))
                .isInstanceOf(McpToolParamInvalidException.class)
                .hasMessageContaining("start");
    }

    // ── get_ticker ──

    @Test
    void getTicker_validParams_shouldDelegateAndProject() {
        Instant ts = Instant.parse("2024-01-01T00:00:00Z");
        Ticker t = new Ticker(
                Exchange.OKX,
                MarketType.PERP,
                "ETH/USDT",
                BigDecimal.valueOf(3000),
                BigDecimal.valueOf(2999),
                BigDecimal.valueOf(3001),
                BigDecimal.valueOf(3100),
                BigDecimal.valueOf(2900),
                BigDecimal.valueOf(2950),
                BigDecimal.valueOf(100),
                BigDecimal.valueOf(300_000),
                BigDecimal.valueOf(50),
                BigDecimal.valueOf(1.5),
                ts,
                ts);
        when(service.getLatestTicker(Exchange.OKX, MarketType.PERP, "ETH/USDT")).thenReturn(t);

        TickerView v = tools.getTicker("okx", "perp", "ETH/USDT");

        assertThat(v.last()).isEqualByComparingTo("3000");
        assertThat(v.symbol()).isEqualTo("ETH/USDT");
        verify(service).getLatestTicker(Exchange.OKX, MarketType.PERP, "ETH/USDT");
    }

    @Test
    void getTicker_invalidMarketType_shouldThrow10002() {
        assertThatThrownBy(() -> tools.getTicker("binance", "futures", "BTC/USDT"))
                .isInstanceOf(McpToolParamInvalidException.class)
                .hasMessageContaining("marketType");
    }

    // ── get_orderbook ──

    @Test
    void getOrderbook_validParams_shouldDelegateAndProject() {
        OrderBook ob = new OrderBook(
                Exchange.BINANCE,
                MarketType.SPOT,
                "BTC/USDT",
                List.of(new OrderBook.PriceLevel(BigDecimal.valueOf(50000), BigDecimal.valueOf(1.5))),
                List.of(new OrderBook.PriceLevel(BigDecimal.valueOf(50001), BigDecimal.valueOf(0.8))),
                Instant.ofEpochMilli(1_700_000_000_000L),
                Instant.now());
        when(service.fetchOrderBook(Exchange.BINANCE, MarketType.SPOT, "BTC/USDT", 20))
                .thenReturn(ob);

        OrderBookView v = tools.getOrderbook("binance", "spot", "BTC/USDT", null);

        assertThat(v.bids()).hasSize(1);
        assertThat(v.bids().get(0).price()).isEqualByComparingTo("50000");
        assertThat(v.asks()).hasSize(1);
        verify(service).fetchOrderBook(Exchange.BINANCE, MarketType.SPOT, "BTC/USDT", 20);
    }

    @Test
    void getOrderbook_explicitLimit_shouldPassThrough() {
        OrderBook ob =
                new OrderBook(Exchange.BINANCE, MarketType.SPOT, "BTC/USDT", List.of(), List.of(), null, Instant.now());
        when(service.fetchOrderBook(Exchange.BINANCE, MarketType.SPOT, "BTC/USDT", 5))
                .thenReturn(ob);

        OrderBookView v = tools.getOrderbook("binance", "spot", "BTC/USDT", 5);

        assertThat(v.bids()).isEmpty();
        verify(service).fetchOrderBook(Exchange.BINANCE, MarketType.SPOT, "BTC/USDT", 5);
    }

    @Test
    void getOrderbook_paperExchange_shouldConvertIllegalArgTo10002() {
        when(service.fetchOrderBook(eq(Exchange.PAPER), any(), any(), anyInt()))
                .thenThrow(new IllegalArgumentException("exchange not configured: PAPER:SPOT"));

        assertThatThrownBy(() -> tools.getOrderbook("paper", "spot", "BTC/USDT", 20))
                .isInstanceOf(McpToolParamInvalidException.class)
                .hasMessageContaining("PAPER");
    }

    // ── get_funding_rate ──

    @Test
    void getFundingRate_spotMarket_shouldThrow10002() {
        assertThatThrownBy(() -> tools.getFundingRate("binance", "spot", "BTC/USDT"))
                .isInstanceOf(McpToolParamInvalidException.class)
                .hasMessageContaining("PERP");
    }

    @Test
    void getFundingRate_perpValid_shouldDelegateAndProject() {
        FundingRate fr = new FundingRate(
                Exchange.BITGET,
                MarketType.PERP,
                "BTC/USDT",
                BigDecimal.valueOf(0.0001),
                BigDecimal.valueOf(50000.5),
                BigDecimal.valueOf(0.00012),
                Instant.ofEpochMilli(1_700_000_000_000L),
                Instant.ofEpochMilli(1_699_999_000_000L),
                Instant.now());
        when(service.fetchFundingRate(Exchange.BITGET, MarketType.PERP, "BTC/USDT"))
                .thenReturn(fr);

        FundingRateView v = tools.getFundingRate("bitget", "perp", "BTC/USDT");

        assertThat(v.fundingRate()).isEqualByComparingTo("0.0001");
        assertThat(v.nextFundingRate()).isEqualByComparingTo("0.00012");
        verify(service).fetchFundingRate(Exchange.BITGET, MarketType.PERP, "BTC/USDT");
    }

    @Test
    void getFundingRate_invalidExchange_shouldThrow10002() {
        assertThatThrownBy(() -> tools.getFundingRate("kraken", "perp", "BTC/USDT"))
                .isInstanceOf(McpToolParamInvalidException.class)
                .hasMessageContaining("exchange");
    }

    @Test
    void getFundingRate_paperExchange_shouldConvertIllegalArgTo10002() {
        when(service.fetchFundingRate(eq(Exchange.PAPER), any(), any()))
                .thenThrow(new IllegalArgumentException("exchange not configured: PAPER:PERP"));

        assertThatThrownBy(() -> tools.getFundingRate("paper", "perp", "BTC/USDT"))
                .isInstanceOf(McpToolParamInvalidException.class)
                .hasMessageContaining("PAPER");
    }
}
