package com.kwikquant.report.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.kwikquant.account.application.ExchangeAccountService;
import com.kwikquant.shared.types.OrderSide;
import com.kwikquant.shared.types.OrderStatus;
import com.kwikquant.shared.types.OrderType;
import com.kwikquant.trading.domain.Fill;
import com.kwikquant.trading.domain.Order;
import com.kwikquant.trading.infrastructure.FillMapper;
import com.kwikquant.trading.infrastructure.OrderMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TradeHistoryServiceTest {

    private OrderMapper orderMapper;
    private FillMapper fillMapper;
    private ExchangeAccountService accountService;
    private TradeHistoryService service;

    private static final long USER_ID = 42L;
    private static final long ACCOUNT_ID = 10L;

    @BeforeEach
    void setUp() {
        orderMapper = mock(OrderMapper.class);
        fillMapper = mock(FillMapper.class);
        accountService = mock(ExchangeAccountService.class);
        service = new TradeHistoryService(orderMapper, fillMapper, accountService);
    }

    @Test
    void query_withAccountId_returnsPagedItems() {
        Order order = sampleOrder(1L, ACCOUNT_ID, "BTC/USDT", OrderSide.BUY, OrderStatus.FILLED);
        Fill fill = sampleFill(1L, "50000", "0.1", "0.5");

        when(orderMapper.countByQuery(eq(ACCOUNT_ID), isNull(), any(), isNull(), isNull()))
                .thenReturn(1L);
        when(orderMapper.findByQuery(eq(ACCOUNT_ID), isNull(), any(), isNull(), isNull(), eq(20), eq(0)))
                .thenReturn(List.of(order));
        when(fillMapper.findByOrderId(1L)).thenReturn(List.of(fill));

        var result = service.query(USER_ID, ACCOUNT_ID, null, null, null, 1, 20);

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

        when(orderMapper.countByQuery(eq(ACCOUNT_ID), isNull(), any(), isNull(), isNull()))
                .thenReturn(0L);
        when(orderMapper.findByQuery(eq(ACCOUNT_ID), isNull(), any(), isNull(), isNull(), eq(20), eq(0)))
                .thenReturn(List.of());

        var result = service.query(USER_ID, null, null, null, null, 1, 20);

        assertThat(result.content()).isEmpty();
        org.mockito.Mockito.verify(accountService).listByUser(USER_ID);
    }

    @Test
    void query_noOrders_emptyPage() {
        when(orderMapper.countByQuery(eq(ACCOUNT_ID), isNull(), any(), isNull(), isNull()))
                .thenReturn(0L);
        when(orderMapper.findByQuery(eq(ACCOUNT_ID), isNull(), any(), isNull(), isNull(), eq(20), eq(0)))
                .thenReturn(List.of());

        var result = service.query(USER_ID, ACCOUNT_ID, null, null, null, 1, 20);

        assertThat(result.content()).isEmpty();
        assertThat(result.total()).isZero();
    }

    @Test
    void query_multipleFills_aggregatesFeeAndVolume() {
        Order order = sampleOrder(1L, ACCOUNT_ID, "ETH/USDT", OrderSide.SELL, OrderStatus.FILLED);
        Fill fill1 = sampleFill(1L, "3000", "1", "0.3");
        Fill fill2 = sampleFill(1L, "3100", "0.5", "0.2");

        when(orderMapper.countByQuery(eq(ACCOUNT_ID), isNull(), any(), isNull(), isNull()))
                .thenReturn(1L);
        when(orderMapper.findByQuery(eq(ACCOUNT_ID), isNull(), any(), isNull(), isNull(), eq(20), eq(0)))
                .thenReturn(List.of(order));
        when(fillMapper.findByOrderId(1L)).thenReturn(List.of(fill1, fill2));

        var result = service.query(USER_ID, ACCOUNT_ID, null, null, null, 1, 20);

        TradeHistoryService.TradeHistoryItem item = result.content().getFirst();
        // totalFee = 0.3 + 0.2 = 0.5
        assertThat(item.totalFee()).isEqualByComparingTo("0.5");
        // totalVolume = 3000*1 + 3100*0.5 = 3000 + 1550 = 4550
        assertThat(item.totalVolume()).isEqualByComparingTo("4550");
    }

    @Test
    void stats_withAccountId_returnsStatistics() {
        Order filledOrder = sampleOrder(1L, ACCOUNT_ID, "BTC/USDT", OrderSide.SELL, OrderStatus.FILLED);
        Fill fill = sampleFill(1L, "50000", "0.1", "2.5");

        when(orderMapper.findByQuery(eq(ACCOUNT_ID), isNull(), any(), any(), isNull(), anyInt(), eq(0)))
                .thenReturn(List.of(filledOrder));
        when(fillMapper.findByOrderId(1L)).thenReturn(List.of(fill));
        when(fillMapper.sumNetCashflow(eq(ACCOUNT_ID), any(Instant.class))).thenReturn(new BigDecimal("500"));

        var stats = service.stats(USER_ID, ACCOUNT_ID, null);

        // totalVolume = 50000 * 0.1 = 5000
        assertThat(stats.totalVolume()).isEqualByComparingTo("5000");
        assertThat(stats.totalFees()).isEqualByComparingTo("2.5");
        assertThat(stats.realizedPnl()).isEqualByComparingTo("500");
    }

    @Test
    void stats_noAccountId_aggregatesAllAccounts() {
        ExchangeAccountService.ExchangeAccountView view =
                new ExchangeAccountService.ExchangeAccountView(ACCOUNT_ID, null, "label", "key", false, "ACTIVE");
        when(accountService.listByUser(USER_ID)).thenReturn(List.of(view));

        when(orderMapper.findByQuery(eq(ACCOUNT_ID), isNull(), any(), any(), isNull(), anyInt(), eq(0)))
                .thenReturn(List.of());
        when(fillMapper.sumNetCashflow(eq(ACCOUNT_ID), any(Instant.class))).thenReturn(BigDecimal.ZERO);

        var stats = service.stats(USER_ID, null, null);

        assertThat(stats.totalVolume()).isEqualByComparingTo("0");
        assertThat(stats.totalFees()).isEqualByComparingTo("0");
        assertThat(stats.realizedPnl()).isEqualByComparingTo("0");
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
