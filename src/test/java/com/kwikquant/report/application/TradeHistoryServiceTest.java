package com.kwikquant.report.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.kwikquant.account.application.ExchangeAccountService;
import com.kwikquant.shared.types.OrderSide;
import com.kwikquant.shared.types.OrderStatus;
import com.kwikquant.shared.types.OrderType;
import com.kwikquant.shared.types.PageQuery;
import com.kwikquant.trading.application.TradingService;
import com.kwikquant.trading.application.VolumeAndFees;
import com.kwikquant.trading.domain.Fill;
import com.kwikquant.trading.domain.Order;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TradeHistoryServiceTest {

    private TradingService tradingService;
    private ExchangeAccountService accountService;
    private TradeHistoryService service;

    private static final long USER_ID = 42L;
    private static final long ACCOUNT_ID = 10L;

    @BeforeEach
    void setUp() {
        tradingService = mock(TradingService.class);
        accountService = mock(ExchangeAccountService.class);
        service = new TradeHistoryService(tradingService, accountService);
    }

    @Test
    void query_withAccountId_returnsPagedItems() {
        Order order = sampleOrder(1L, ACCOUNT_ID, "BTC/USDT", OrderSide.BUY, OrderStatus.FILLED);
        Fill fill = sampleFill(1L, "50000", "0.1", "0.5");

        when(tradingService.countOrders(eq(ACCOUNT_ID), isNull(), any(), isNull(), isNull()))
                .thenReturn(1L);
        when(tradingService.queryOrders(eq(ACCOUNT_ID), isNull(), any(), isNull(), isNull(), eq(20), eq(0)))
                .thenReturn(List.of(order));
        // 批量查询 fills（新 API）
        when(tradingService.listFillsByOrders(List.of(1L))).thenReturn(List.of(fill));

        var result = service.query(USER_ID, ACCOUNT_ID, null, null, null, PageQuery.of(1, 20, 20, 100));

        assertThat(result.content()).hasSize(1);
        assertThat(result.total()).isEqualTo(1);

        TradeHistoryService.TradeHistoryItem item = result.content().getFirst();
        assertThat(item.orderId()).isEqualTo(1L);
        assertThat(item.accountId()).isEqualTo(ACCOUNT_ID);
        assertThat(item.symbol()).isEqualTo("BTC/USDT");
        assertThat(item.side()).isEqualTo("BUY");
        assertThat(item.orderType()).isEqualTo("LIMIT");
        assertThat(item.filledAvgPrice()).isEqualByComparingTo("50000");
        assertThat(item.totalFee()).isEqualByComparingTo("0.5");
        assertThat(item.totalVolume()).isEqualByComparingTo("5000");
        assertThat(item.status()).isEqualTo("FILLED");
        assertThat(item.createdAt()).isNotNull();
        assertThat(item.updatedAt()).isNotNull();

        // verify ownership check was called
        org.mockito.Mockito.verify(accountService).getOwned(ACCOUNT_ID, USER_ID);
    }

    @Test
    void query_noAccountId_queriesAllAccounts() {
        ExchangeAccountService.ExchangeAccountView view =
                new ExchangeAccountService.ExchangeAccountView(ACCOUNT_ID, null, "label", "key", false, "ACTIVE");
        when(accountService.listByUser(USER_ID)).thenReturn(List.of(view));

        when(tradingService.countOrders(eq(ACCOUNT_ID), isNull(), any(), isNull(), isNull()))
                .thenReturn(0L);
        when(tradingService.queryOrders(eq(ACCOUNT_ID), isNull(), any(), isNull(), isNull(), eq(20), eq(0)))
                .thenReturn(List.of());
        when(tradingService.listFillsByOrders(any())).thenReturn(List.of());

        var result = service.query(USER_ID, null, null, null, null, PageQuery.of(1, 20, 20, 100));

        assertThat(result.content()).isEmpty();
        org.mockito.Mockito.verify(accountService).listByUser(USER_ID);
    }

    @Test
    void query_noOrders_emptyPage() {
        when(tradingService.countOrders(eq(ACCOUNT_ID), isNull(), any(), isNull(), isNull()))
                .thenReturn(0L);
        when(tradingService.queryOrders(eq(ACCOUNT_ID), isNull(), any(), isNull(), isNull(), eq(20), eq(0)))
                .thenReturn(List.of());
        when(tradingService.listFillsByOrders(any())).thenReturn(List.of());

        var result = service.query(USER_ID, ACCOUNT_ID, null, null, null, PageQuery.of(1, 20, 20, 100));

        assertThat(result.content()).isEmpty();
        assertThat(result.total()).isZero();
    }

    @Test
    void query_multipleFills_aggregatesFeeAndVolume() {
        Order order = sampleOrder(1L, ACCOUNT_ID, "ETH/USDT", OrderSide.SELL, OrderStatus.FILLED);
        Fill fill1 = sampleFill(1L, "3000", "1", "0.3");
        Fill fill2 = sampleFill(1L, "3100", "0.5", "0.2");

        when(tradingService.countOrders(eq(ACCOUNT_ID), isNull(), any(), isNull(), isNull()))
                .thenReturn(1L);
        when(tradingService.queryOrders(eq(ACCOUNT_ID), isNull(), any(), isNull(), isNull(), eq(20), eq(0)))
                .thenReturn(List.of(order));
        when(tradingService.listFillsByOrders(List.of(1L))).thenReturn(List.of(fill1, fill2));

        var result = service.query(USER_ID, ACCOUNT_ID, null, null, null, PageQuery.of(1, 20, 20, 100));

        TradeHistoryService.TradeHistoryItem item = result.content().getFirst();
        // totalFee = 0.3 + 0.2 = 0.5
        assertThat(item.totalFee()).isEqualByComparingTo("0.5");
        // totalVolume = 3000*1 + 3100*0.5 = 3000 + 1550 = 4550
        assertThat(item.totalVolume()).isEqualByComparingTo("4550");
    }

    @Test
    void queryAll_returnsAllItems_noTruncation() {
        Order o1 = sampleOrder(1L, ACCOUNT_ID, "BTC/USDT", OrderSide.BUY, OrderStatus.FILLED);
        Order o2 = sampleOrder(2L, ACCOUNT_ID, "ETH/USDT", OrderSide.SELL, OrderStatus.FILLED);

        when(tradingService.countOrders(eq(ACCOUNT_ID), isNull(), any(), isNull(), isNull()))
                .thenReturn(2L);
        // queryAll 应使用 count 作为 limit，不应被 10000 截断
        when(tradingService.queryOrders(eq(ACCOUNT_ID), isNull(), any(), isNull(), isNull(), eq(2), eq(0)))
                .thenReturn(List.of(o1, o2));
        when(tradingService.listFillsByOrders(any())).thenReturn(List.of());

        List<TradeHistoryService.TradeHistoryItem> items = service.queryAll(USER_ID, ACCOUNT_ID, null, null, null);

        assertThat(items).hasSize(2);
        assertThat(items.get(0).orderId()).isEqualTo(1L);
        assertThat(items.get(1).orderId()).isEqualTo(2L);
    }

    @Test
    void stats_withAccountId_returnsStatistics() {
        // stats 现在用聚合 SQL，不再查 orders + fills 循环
        when(tradingService.sumVolumeAndFees(eq(ACCOUNT_ID), any(Instant.class)))
                .thenReturn(new VolumeAndFees(new BigDecimal("5000"), new BigDecimal("2.5")));
        when(tradingService.sumNetCashflow(eq(ACCOUNT_ID), any(Instant.class))).thenReturn(new BigDecimal("500"));
        when(tradingService.countDailyWinLoss(eq(ACCOUNT_ID), any(Instant.class)))
                .thenReturn(new com.kwikquant.trading.application.TradingService.DailyWinLossResult(5, 3));

        var stats = service.stats(USER_ID, ACCOUNT_ID, null, null);

        assertThat(stats.totalVolume()).isEqualByComparingTo("5000");
        assertThat(stats.totalFees()).isEqualByComparingTo("2.5");
        assertThat(stats.realizedPnl()).isEqualByComparingTo("500");
        assertThat(stats.tradingDays()).isEqualTo(5);
        // 3/5 = 0.6000
        assertThat(stats.winRate()).isEqualByComparingTo("0.6000");
    }

    @Test
    void stats_noAccountId_aggregatesAllAccounts() {
        ExchangeAccountService.ExchangeAccountView view =
                new ExchangeAccountService.ExchangeAccountView(ACCOUNT_ID, null, "label", "key", false, "ACTIVE");
        when(accountService.listByUser(USER_ID)).thenReturn(List.of(view));

        when(tradingService.sumVolumeAndFees(eq(ACCOUNT_ID), any(Instant.class)))
                .thenReturn(new VolumeAndFees(BigDecimal.ZERO, BigDecimal.ZERO));
        when(tradingService.sumNetCashflow(eq(ACCOUNT_ID), any(Instant.class))).thenReturn(BigDecimal.ZERO);
        when(tradingService.countDailyWinLoss(eq(ACCOUNT_ID), any(Instant.class)))
                .thenReturn(new com.kwikquant.trading.application.TradingService.DailyWinLossResult(0, 0));

        var stats = service.stats(USER_ID, null, null, null);

        assertThat(stats.totalVolume()).isEqualByComparingTo("0");
        assertThat(stats.totalFees()).isEqualByComparingTo("0");
        assertThat(stats.realizedPnl()).isEqualByComparingTo("0");
        assertThat(stats.tradingDays()).isZero();
        assertThat(stats.winRate()).isNull();
    }

    @Test
    void stats_multiAccountMultiDay_shouldAggregateWinRateCorrectly() {
        long acct1 = 10L;
        long acct2 = 20L;
        ExchangeAccountService.ExchangeAccountView view1 =
                new ExchangeAccountService.ExchangeAccountView(acct1, null, "a1", "k1", false, "ACTIVE");
        ExchangeAccountService.ExchangeAccountView view2 =
                new ExchangeAccountService.ExchangeAccountView(acct2, null, "a2", "k2", false, "ACTIVE");
        when(accountService.listByUser(USER_ID)).thenReturn(List.of(view1, view2));

        when(tradingService.sumVolumeAndFees(any(Long.class), any(Instant.class)))
                .thenReturn(new VolumeAndFees(new BigDecimal("1000"), new BigDecimal("1")));
        when(tradingService.sumNetCashflow(any(Long.class), any(Instant.class))).thenReturn(new BigDecimal("100"));
        // acct1: 4 total days, 3 win days
        when(tradingService.countDailyWinLoss(eq(acct1), any(Instant.class)))
                .thenReturn(new com.kwikquant.trading.application.TradingService.DailyWinLossResult(4, 3));
        // acct2: 3 total days, 1 win day
        when(tradingService.countDailyWinLoss(eq(acct2), any(Instant.class)))
                .thenReturn(new com.kwikquant.trading.application.TradingService.DailyWinLossResult(3, 1));

        var stats = service.stats(USER_ID, null, null, null);

        // totalDays = 4+3 = 7, winDays = 3+1 = 4
        assertThat(stats.tradingDays()).isEqualTo(7);
        // 4/7 = 0.5714 (HALF_UP, scale 4)
        assertThat(stats.winRate()).isEqualByComparingTo("0.5714");
    }

    @Test
    void stats_noTrades_shouldReturnZeroCountNullWinRate() {
        when(tradingService.sumVolumeAndFees(eq(ACCOUNT_ID), any(Instant.class)))
                .thenReturn(new VolumeAndFees(BigDecimal.ZERO, BigDecimal.ZERO));
        when(tradingService.sumNetCashflow(eq(ACCOUNT_ID), any(Instant.class))).thenReturn(BigDecimal.ZERO);
        when(tradingService.countDailyWinLoss(eq(ACCOUNT_ID), any(Instant.class)))
                .thenReturn(new com.kwikquant.trading.application.TradingService.DailyWinLossResult(0, 0));

        var stats = service.stats(USER_ID, ACCOUNT_ID, null, null);

        assertThat(stats.tradingDays()).isZero();
        assertThat(stats.winRate()).isNull();
    }

    @Test
    void stats_singleDayAllProfitable_shouldReturnWinRateOne() {
        when(tradingService.sumVolumeAndFees(eq(ACCOUNT_ID), any(Instant.class)))
                .thenReturn(new VolumeAndFees(new BigDecimal("500"), new BigDecimal("1")));
        when(tradingService.sumNetCashflow(eq(ACCOUNT_ID), any(Instant.class))).thenReturn(new BigDecimal("499"));
        when(tradingService.countDailyWinLoss(eq(ACCOUNT_ID), any(Instant.class)))
                .thenReturn(new com.kwikquant.trading.application.TradingService.DailyWinLossResult(1, 1));

        var stats = service.stats(USER_ID, ACCOUNT_ID, null, null);

        assertThat(stats.tradingDays()).isEqualTo(1);
        // 1/1 = 1.0000
        assertThat(stats.winRate()).isEqualByComparingTo("1.0000");
    }

    // ---------- helpers ----------

    private static Order sampleOrder(long id, long accountId, String symbol, OrderSide side, OrderStatus status) {
        Order o = new Order();
        o.setId(id);
        o.setAccountId(accountId);
        o.setSymbol(symbol);
        o.setSide(side);
        o.setOrderType(OrderType.LIMIT);
        o.setStatus(status);
        o.setAmount(new BigDecimal("0.1"));
        o.setFilledQty(new BigDecimal("0.1"));
        o.setFilledAvgPrice(new BigDecimal("50000"));
        o.setCreatedAt(Instant.parse("2025-03-01T10:00:00Z"));
        o.setUpdatedAt(Instant.parse("2025-03-01T10:05:00Z"));
        return o;
    }

    private static Fill sampleFill(long orderId, String price, String qty, String fee) {
        Fill f = new Fill();
        f.setOrderId(orderId);
        f.setPrice(new BigDecimal(price));
        f.setQty(new BigDecimal(qty));
        f.setFee(new BigDecimal(fee));
        return f;
    }
}
