package com.kwikquant.trading.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.kwikquant.account.domain.ExchangeAccount;
import com.kwikquant.market.application.MarketDataService;
import com.kwikquant.market.domain.Ticker;
import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.MarketType;
import com.kwikquant.shared.types.OrderSide;
import com.kwikquant.shared.types.OrderType;
import com.kwikquant.trading.infrastructure.FillMapper;
import com.kwikquant.trading.infrastructure.OrderMapper;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 单测 {@link OrderMetricsService}：notional 三分支、resolveMarketPrice 取价条件、
 * countRecentOrders / dailyRealizedPnl 委托签名。覆盖 dry-run 与 submit 共享计算路径的纯逻辑。
 */
class OrderMetricsServiceTest {

    private OrderMapper orderMapper;
    private FillMapper fillMapper;
    private MarketDataService marketDataService;
    private OrderMetricsService service;

    @BeforeEach
    void setUp() {
        orderMapper = mock(OrderMapper.class);
        fillMapper = mock(FillMapper.class);
        marketDataService = mock(MarketDataService.class);
        service = new OrderMetricsService(orderMapper, fillMapper, marketDataService);
    }

    private static ExchangeAccount account(Exchange ex) {
        ExchangeAccount a = new ExchangeAccount();
        a.setExchange(ex);
        return a;
    }

    // ---- notional ----

    @Test
    void notional_usesLimitPriceWhenProvided() {
        BigDecimal n = service.notional(new BigDecimal("0.1"), new BigDecimal("42000"), new BigDecimal("50000"));
        assertThat(n).isEqualByComparingTo("4200");
    }

    @Test
    void notional_fallsBackToMarketPriceWhenLimitNull() {
        BigDecimal n = service.notional(new BigDecimal("0.1"), null, new BigDecimal("50000"));
        assertThat(n).isEqualByComparingTo("5000");
    }

    @Test
    void notional_nullWhenNoPriceAvailable() {
        assertThat(service.notional(new BigDecimal("0.1"), null, null)).isNull();
    }

    // ---- resolveMarketPrice ----

    @Test
    void resolveMarketPrice_buyMarketOrder_fetchesTickerLast() {
        ExchangeAccount a = account(Exchange.BINANCE);
        Ticker t = new Ticker(
                Exchange.BINANCE,
                MarketType.SPOT,
                "BTC/USDT",
                new BigDecimal("50000"),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                Instant.now(),
                Instant.now());
        when(marketDataService.getLatestTicker(Exchange.BINANCE, MarketType.SPOT, "BTC/USDT"))
                .thenReturn(t);

        BigDecimal price = service.resolveMarketPrice(a, OrderSide.BUY, "BTC/USDT", MarketType.SPOT, null);

        assertThat(price).isEqualByComparingTo("50000");
    }

    @Test
    void resolveMarketPrice_limitOrder_returnsNullWithoutFetch() {
        ExchangeAccount a = account(Exchange.BINANCE);
        BigDecimal price =
                service.resolveMarketPrice(a, OrderSide.BUY, "BTC/USDT", MarketType.SPOT, new BigDecimal("42000"));
        assertThat(price).isNull();
        verifyNoInteractions(marketDataService);
    }

    @Test
    void resolveMarketPrice_marketSell_returnsNullWithoutFetch() {
        ExchangeAccount a = account(Exchange.BINANCE);
        BigDecimal price = service.resolveMarketPrice(a, OrderSide.SELL, "BTC/USDT", MarketType.SPOT, null);
        assertThat(price).isNull();
        verifyNoInteractions(marketDataService);
    }

    @Test
    void resolveMarketPrice_buyMarketButTickerStale_returnsNull() {
        ExchangeAccount a = account(Exchange.BINANCE);
        when(marketDataService.getLatestTicker(Exchange.BINANCE, MarketType.SPOT, "BTC/USDT"))
                .thenReturn(null);
        assertThat(service.resolveMarketPrice(a, OrderSide.BUY, "BTC/USDT", MarketType.SPOT, null))
                .isNull();
    }

    // ---- countRecentOrders / dailyRealizedPnl ----

    @Test
    void countRecentOrders_delegatesWithLastMinuteWindow() {
        when(orderMapper.countByAccountSince(eq(7L), any(Instant.class))).thenReturn(3L);
        assertThat(service.countRecentOrders(7L)).isEqualTo(3);
    }

    @Test
    void dailyRealizedPnl_delegatesWithDayTruncatedInstant() {
        when(fillMapper.sumNetCashflow(eq(7L), any(Instant.class))).thenReturn(new BigDecimal("-120"));
        assertThat(service.dailyRealizedPnl(7L)).isEqualByComparingTo("-120");
    }

    // ---- marketBuyLacksPrice（submit 与 dry-run 共享判定）----

    @Test
    void marketBuyLacksPrice_marketBuyNullPrice_returnsTrue() {
        assertThat(service.marketBuyLacksPrice(OrderType.MARKET, OrderSide.BUY, null))
                .isTrue();
    }

    @Test
    void marketBuyLacksPrice_limitOrder_returnsFalse() {
        assertThat(service.marketBuyLacksPrice(OrderType.LIMIT, OrderSide.BUY, null))
                .isFalse();
    }

    @Test
    void marketBuyLacksPrice_marketSell_returnsFalse() {
        assertThat(service.marketBuyLacksPrice(OrderType.MARKET, OrderSide.SELL, null))
                .isFalse();
    }

    @Test
    void marketBuyLacksPrice_marketBuyWithPrice_returnsFalse() {
        assertThat(service.marketBuyLacksPrice(OrderType.MARKET, OrderSide.BUY, new BigDecimal("50000")))
                .isFalse();
    }

    // ---- previewRecentOrderCount（dry-run +1 还原 submit N+1）----

    @Test
    void previewRecentOrderCount_isCountPlusOne() {
        when(orderMapper.countByAccountSince(eq(7L), any(Instant.class))).thenReturn(3L);
        // dry-run 预演：模拟"提交此单后"计数 = 3 + 1 = 4（submit 在 insertOrder 后必然含当前单）
        assertThat(service.previewRecentOrderCount(7L)).isEqualTo(4);
    }
}
