package com.kwikquant.trading.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.kwikquant.account.application.ExchangeAccountService;
import com.kwikquant.market.application.MarketDataService;
import com.kwikquant.market.domain.Ticker;
import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.MarketType;
import com.kwikquant.shared.types.OrderSide;
import com.kwikquant.shared.types.OrderStatus;
import com.kwikquant.shared.types.OrderType;
import com.kwikquant.trading.domain.Order;
import com.kwikquant.trading.infrastructure.OrderMapper;
import com.kwikquant.trading.interfaces.OrderWebSocketBroadcaster;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PaperExecutorTest {
    private MarketDataService marketDataService;
    private OrderMapper orderMapper;
    private ExecutionService executionService;
    private OrderWebSocketBroadcaster wsBroadcaster;
    private ExchangeAccountService accountService;
    private PaperExecutor executor;

    @BeforeEach
    void setUp() {
        marketDataService = mock(MarketDataService.class);
        orderMapper = mock(OrderMapper.class);
        executionService = mock(ExecutionService.class);
        wsBroadcaster = mock(OrderWebSocketBroadcaster.class);
        accountService = mock(ExchangeAccountService.class);
        executor = new PaperExecutor(marketDataService, orderMapper, executionService, wsBroadcaster, accountService);
    }

    @Test
    void submit_transitionsToSubmittedAndAddsToActivePool() {
        Order order = order(1L, OrderStatus.NEW);
        when(orderMapper.casUpdate(any())).thenReturn(1);
        executor.submit(order);
        assertThat(executor.activeOrderCount()).isEqualTo(1);
        verify(orderMapper, times(2)).casUpdate(any());
    }

    @Test
    void submit_whenFirstCasFails_doesNotAddToPool() {
        Order order = order(1L, OrderStatus.NEW);
        when(orderMapper.casUpdate(any())).thenReturn(0);
        executor.submit(order);
        assertThat(executor.activeOrderCount()).isEqualTo(0);
    }

    @Test
    void submit_whenSecondCasFails_attemptsRecovery() {
        Order order = order(1L, OrderStatus.NEW);
        Order reloaded = order(1L, OrderStatus.PENDING_NEW);
        reloaded.setVersion(2L);
        when(orderMapper.casUpdate(any())).thenReturn(1).thenReturn(0).thenReturn(1);
        when(orderMapper.findById(1L)).thenReturn(reloaded);
        executor.submit(order);
        assertThat(executor.activeOrderCount()).isEqualTo(1);
    }

    @Test
    void cancel_transitionsToCancelledAndRemovesFromPool() {
        Order order = order(1L, OrderStatus.NEW);
        when(orderMapper.casUpdate(any())).thenReturn(1);
        executor.submit(order);
        assertThat(executor.activeOrderCount()).isEqualTo(1);
        Order cancelOrder = order(1L, OrderStatus.PENDING_CANCEL);
        cancelOrder.setVersion(3L);
        executor.cancel(cancelOrder);
        assertThat(executor.activeOrderCount()).isEqualTo(0);
    }

    @Test
    void cancel_whenCasFails_noException() {
        Order order = order(1L, OrderStatus.PENDING_CANCEL);
        when(orderMapper.casUpdate(any())).thenReturn(0);
        assertThatCode(() -> executor.cancel(order)).doesNotThrowAnyException();
    }

    @Test
    void bootstrapActivePaperOrders_loadsFromDb() {
        Order o1 = order(1L, OrderStatus.SUBMITTED);
        Order o2 = order(2L, OrderStatus.SUBMITTED);
        when(orderMapper.findActiveByAccount(42L)).thenReturn(List.of(o1, o2));
        executor.bootstrapActivePaperOrders(42L);
        assertThat(executor.activeOrderCount()).isEqualTo(2);
    }

    @Test
    void bootstrapActivePaperOrders_whenEmpty_noOp() {
        when(orderMapper.findActiveByAccount(42L)).thenReturn(List.of());
        executor.bootstrapActivePaperOrders(42L);
        assertThat(executor.activeOrderCount()).isEqualTo(0);
    }

    @Test
    void broadcastOrderEvent_whenAccountLookupFails_doesNotThrow() {
        Order order = order(1L, OrderStatus.NEW);
        when(orderMapper.casUpdate(any())).thenReturn(1);
        when(accountService.findById(anyLong())).thenThrow(new RuntimeException("DB down"));
        assertThatCode(() -> executor.submit(order)).doesNotThrowAnyException();
    }

    @Test
    void onTicker_whenMatchingFill_processesExecutionReport() {
        // Submit a BUY LIMIT order into the active pool
        Order order = order(1L, OrderStatus.NEW);
        order.setSide(OrderSide.BUY);
        order.setOrderType(OrderType.LIMIT);
        order.setPrice(new BigDecimal("40000"));
        order.setAmount(new BigDecimal("1"));
        when(orderMapper.casUpdate(any())).thenReturn(1);
        executor.submit(order);
        assertThat(executor.activeOrderCount()).isEqualTo(1);

        // Ticker with last=39000 < limit=40000 → should match BUY LIMIT
        Ticker ticker = new Ticker(
                Exchange.BINANCE,
                MarketType.SPOT,
                "BTC/USDT",
                new BigDecimal("39000"),
                new BigDecimal("38900"),
                new BigDecimal("39100"),
                new BigDecimal("41000"),
                new BigDecimal("38000"),
                new BigDecimal("40000"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                Instant.now(),
                Instant.now());

        // After match, order becomes FILLED (terminal) → removed from pool
        Order filled = order(1L, OrderStatus.FILLED);
        when(orderMapper.findById(1L)).thenReturn(filled);

        executor.onTicker(ticker);

        verify(executionService).processExecutionReport(any());
        assertThat(executor.activeOrderCount()).isEqualTo(0);
    }

    @Test
    void onTicker_whenNoMatch_keepsInPool() {
        Order order = order(1L, OrderStatus.NEW);
        order.setSide(OrderSide.BUY);
        order.setOrderType(OrderType.LIMIT);
        order.setPrice(new BigDecimal("30000")); // low limit
        order.setAmount(new BigDecimal("1"));
        when(orderMapper.casUpdate(any())).thenReturn(1);
        executor.submit(order);

        // Ticker with last=40000 > limit=30000 → no match for BUY LIMIT
        Ticker ticker = new Ticker(
                Exchange.BINANCE,
                MarketType.SPOT,
                "BTC/USDT",
                new BigDecimal("40000"),
                new BigDecimal("39900"),
                new BigDecimal("40100"),
                new BigDecimal("41000"),
                new BigDecimal("38000"),
                new BigDecimal("40000"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                Instant.now(),
                Instant.now());

        executor.onTicker(ticker);

        verify(executionService, never()).processExecutionReport(any());
        assertThat(executor.activeOrderCount()).isEqualTo(1);
    }

    @Test
    void onTicker_whenTerminalOrder_removesFromPool() {
        Order order = order(1L, OrderStatus.NEW);
        when(orderMapper.casUpdate(any())).thenReturn(1);
        executor.submit(order);

        // Manually set terminal status
        order.setStatus(OrderStatus.CANCELLED);

        Ticker ticker = new Ticker(
                Exchange.BINANCE,
                MarketType.SPOT,
                "BTC/USDT",
                new BigDecimal("40000"),
                new BigDecimal("39900"),
                new BigDecimal("40100"),
                new BigDecimal("41000"),
                new BigDecimal("38000"),
                new BigDecimal("40000"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                Instant.now(),
                Instant.now());

        executor.onTicker(ticker);

        assertThat(executor.activeOrderCount()).isEqualTo(0);
    }

    @Test
    void onTicker_whenDifferentSymbol_skips() {
        Order order = order(1L, OrderStatus.NEW);
        order.setSymbol("ETH/USDT");
        when(orderMapper.casUpdate(any())).thenReturn(1);
        executor.submit(order);

        Ticker ticker = new Ticker(
                Exchange.BINANCE,
                MarketType.SPOT,
                "BTC/USDT",
                new BigDecimal("40000"),
                new BigDecimal("39900"),
                new BigDecimal("40100"),
                new BigDecimal("41000"),
                new BigDecimal("38000"),
                new BigDecimal("40000"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                Instant.now(),
                Instant.now());

        executor.onTicker(ticker);

        // ETH/USDT order not affected by BTC/USDT ticker
        assertThat(executor.activeOrderCount()).isEqualTo(1);
    }

    /**
     * Batch 6b: 基准交易所过滤。order.referenceExchange=BINANCE,ticker 来自 OKX(同 symbol BTC/USDT,
     * 价格 39000 < limit 40000 无过滤会撮合)→ 必须跳过,避免多交易所配置下重复撮合。
     */
    @Test
    void onTicker_whenDifferentReferenceExchange_skips() {
        Order order = order(1L, OrderStatus.NEW);
        order.setSide(OrderSide.BUY);
        order.setOrderType(OrderType.LIMIT);
        order.setPrice(new BigDecimal("40000"));
        order.setAmount(new BigDecimal("1"));
        when(orderMapper.casUpdate(any())).thenReturn(1);
        executor.submit(order);
        assertThat(executor.activeOrderCount()).isEqualTo(1);

        Ticker okxTicker = new Ticker(
                Exchange.OKX,
                MarketType.SPOT,
                "BTC/USDT",
                new BigDecimal("39000"),
                new BigDecimal("38900"),
                new BigDecimal("39100"),
                new BigDecimal("41000"),
                new BigDecimal("38000"),
                new BigDecimal("40000"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                Instant.now(),
                Instant.now());

        executor.onTicker(okxTicker);

        verify(executionService, never()).processExecutionReport(any());
        assertThat(executor.activeOrderCount()).isEqualTo(1);
    }

    @Test
    void onTicker_whenMatchThrows_continuesOtherOrders() {
        Order good = order(1L, OrderStatus.NEW);
        good.setSide(OrderSide.BUY);
        good.setOrderType(OrderType.LIMIT);
        good.setPrice(new BigDecimal("50000"));
        good.setAmount(new BigDecimal("1"));
        when(orderMapper.casUpdate(any())).thenReturn(1);
        executor.submit(good);

        Ticker ticker = new Ticker(
                Exchange.BINANCE,
                MarketType.SPOT,
                "BTC/USDT",
                new BigDecimal("40000"),
                new BigDecimal("39900"),
                new BigDecimal("40100"),
                new BigDecimal("41000"),
                new BigDecimal("38000"),
                new BigDecimal("40000"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                Instant.now(),
                Instant.now());

        // processExecutionReport throws → should not propagate
        doThrow(new RuntimeException("processing error")).when(executionService).processExecutionReport(any());
        Order reloaded = order(1L, OrderStatus.SUBMITTED);
        when(orderMapper.findById(1L)).thenReturn(reloaded);

        assertThatCode(() -> executor.onTicker(ticker)).doesNotThrowAnyException();
    }

    @Test
    void onTicker_whenMatchButNotTerminal_updatesPool() {
        // Submit a BUY LIMIT with qty=2 (partial fill possible)
        Order order = order(1L, OrderStatus.NEW);
        order.setSide(OrderSide.BUY);
        order.setOrderType(OrderType.LIMIT);
        order.setPrice(new BigDecimal("50000"));
        order.setAmount(new BigDecimal("2"));
        when(orderMapper.casUpdate(any())).thenReturn(1);
        executor.submit(order);
        assertThat(executor.activeOrderCount()).isEqualTo(1);

        Ticker ticker = new Ticker(
                Exchange.BINANCE,
                MarketType.SPOT,
                "BTC/USDT",
                new BigDecimal("40000"),
                new BigDecimal("39900"),
                new BigDecimal("40100"),
                new BigDecimal("41000"),
                new BigDecimal("38000"),
                new BigDecimal("40000"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                Instant.now(),
                Instant.now());

        // After match, order is PARTIALLY_FILLED (not terminal) → stays in pool via toUpdate
        Order partial = order(1L, OrderStatus.PARTIALLY_FILLED);
        partial.setFilledQty(new BigDecimal("1"));
        when(orderMapper.findById(1L)).thenReturn(partial);

        executor.onTicker(ticker);

        verify(executionService).processExecutionReport(any());
        // Order still in pool (not terminal)
        assertThat(executor.activeOrderCount()).isEqualTo(1);
    }

    /** Batch 6b: 基准交易所过滤——略(在另一测试覆盖)。Task 4b: 清某账户内存活跃订单池。 */
    @Test
    void clearActiveOrdersByAccount_removesOnlyThatAccountsOrders() {
        Order o1 = order(1L, OrderStatus.NEW);
        o1.setAccountId(10L);
        Order o2 = order(2L, OrderStatus.NEW);
        o2.setAccountId(20L);
        when(orderMapper.casUpdate(any())).thenReturn(1);
        executor.submit(o1);
        executor.submit(o2);
        assertThat(executor.activeOrderCount()).isEqualTo(2);

        executor.clearActiveOrdersByAccount(10L);

        assertThat(executor.activeOrderCount()).isEqualTo(1); // 仅留 account 20 的订单
    }

    private Order order(long id, OrderStatus status) {
        Order o = new Order();
        o.setId(id);
        o.setAccountId(1L);
        o.setSymbol("BTC/USDT");
        o.setStatus(status);
        o.setVersion(1L);
        o.setAmount(new BigDecimal("1"));
        o.setFilledQty(BigDecimal.ZERO);
        // Batch 6b: 基准所默认 BINANCE(onTicker 过滤需要非 null;现有测试 ticker 都是 BINANCE)
        o.setReferenceExchange(Exchange.BINANCE);
        return o;
    }
}
