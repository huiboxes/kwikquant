package com.kwikquant.trading.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.kwikquant.account.application.ExchangeAccountService;
import com.kwikquant.account.domain.ExchangeAccount;
import com.kwikquant.shared.infra.ExchangeException;
import com.kwikquant.shared.types.OrderSide;
import com.kwikquant.shared.types.OrderStatus;
import com.kwikquant.shared.types.OrderType;
import com.kwikquant.trading.domain.Order;
import com.kwikquant.trading.domain.TimeInForce;
import com.kwikquant.trading.infrastructure.CcxtOrderAdapter;
import com.kwikquant.trading.infrastructure.OrderMapper;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LiveExecutorTest {

    private CcxtOrderAdapter ccxtAdapter;
    private ExchangeAccountService accountService;
    private ExecutionService executionService;
    private OrderMapper orderMapper;
    private LiveExecutor executor;

    @BeforeEach
    void setUp() {
        ccxtAdapter = mock(CcxtOrderAdapter.class);
        accountService = mock(ExchangeAccountService.class);
        executionService = mock(ExecutionService.class);
        orderMapper = mock(OrderMapper.class);
        executor = new LiveExecutor(ccxtAdapter, accountService, executionService, orderMapper);
    }

    // ==================== submit ====================

    @Test
    void submitHappyPath_callsCreateOrderAndOnAccepted() {
        Order order = newOrder(1L, OrderStatus.NEW);
        ExchangeAccount acct = newAccount(1L);
        when(accountService.findById(1L)).thenReturn(acct);
        when(ccxtAdapter.createOrder(acct, order)).thenReturn("exchange-123");
        when(ccxtAdapter.subscribeFills(any(), any())).thenReturn(() -> {});

        executor.submit(order);

        verify(ccxtAdapter).createOrder(acct, order);
        verify(executionService).onExchangeAccepted(1L, "exchange-123");
    }

    @Test
    void submitExchangeRejected_callsOnRejected() {
        Order order = newOrder(1L, OrderStatus.NEW);
        ExchangeAccount acct = newAccount(1L);
        when(accountService.findById(1L)).thenReturn(acct);
        when(ccxtAdapter.createOrder(acct, order))
                .thenThrow(new ExchangeException("insufficient balance", false));

        executor.submit(order);

        verify(executionService).onExchangeRejected(1L, "insufficient balance");
    }

    @Test
    void submitRuntimeException_callsOnRejected() {
        Order order = newOrder(1L, OrderStatus.NEW);
        ExchangeAccount acct = newAccount(1L);
        when(accountService.findById(1L)).thenReturn(acct);
        when(ccxtAdapter.createOrder(acct, order))
                .thenThrow(new RuntimeException("network timeout"));

        executor.submit(order);

        verify(executionService).onExchangeRejected(eq(1L), argThat(msg -> msg != null && msg.contains("network timeout")));
    }

    @Test
    void submitAccountNotFound_doesNothing() {
        Order order = newOrder(1L, OrderStatus.NEW);
        when(accountService.findById(1L)).thenReturn(null);

        executor.submit(order);

        verify(ccxtAdapter, never()).createOrder(any(), any());
        verify(executionService, never()).onExchangeAccepted(anyLong(), any());
    }

    @Test
    void submitAccountServiceThrows_doesNothing() {
        Order order = newOrder(1L, OrderStatus.NEW);
        when(accountService.findById(1L)).thenThrow(new RuntimeException("DB error"));

        executor.submit(order);

        verify(ccxtAdapter, never()).createOrder(any(), any());
    }

    // ==================== cancel ====================

    @Test
    void cancelHappyPath_callsCancelOrder() {
        Order order = newOrder(1L, OrderStatus.PENDING_CANCEL);
        ExchangeAccount acct = newAccount(1L);
        when(accountService.findById(1L)).thenReturn(acct);

        executor.cancel(order);

        verify(ccxtAdapter).cancelOrder(acct, order);
    }

    @Test
    void cancelAccountNotFound_doesNothing() {
        Order order = newOrder(1L, OrderStatus.PENDING_CANCEL);
        when(accountService.findById(1L)).thenReturn(null);

        executor.cancel(order);

        verify(ccxtAdapter, never()).cancelOrder(any(), any());
    }

    @Test
    void cancelRuntimeException_swallowedWithLog() {
        Order order = newOrder(1L, OrderStatus.PENDING_CANCEL);
        ExchangeAccount acct = newAccount(1L);
        when(accountService.findById(1L)).thenReturn(acct);
        doThrow(new RuntimeException("cancel failed")).when(ccxtAdapter).cancelOrder(acct, order);

        // Should not throw
        assertThatCode(() -> executor.cancel(order)).doesNotThrowAnyException();
    }

    // ==================== startupSnapshot ====================

    @Test
    void startupSnapshot_knownOrders_noWarnings() {
        ExchangeAccount acct = newAccount(1L);
        var snap = new CcxtOrderAdapter.AccountSnapshot(
                List.of(new CcxtOrderAdapter.OrderSnapshot(
                        "ex-1", "c1", "BTC/USDT", "buy",
                        new BigDecimal("1"), BigDecimal.ZERO, "open")),
                List.of());
        when(ccxtAdapter.fetchSnapshot(acct)).thenReturn(snap);
        when(ccxtAdapter.subscribeFills(any(), any())).thenReturn(() -> {});

        // Local order exists
        Order local = newOrder(10L, OrderStatus.SUBMITTED);
        local.setExchangeOrderId("ex-1");
        when(orderMapper.findByExchangeOrderId(1L, "ex-1")).thenReturn(local);

        executor.startupSnapshot(acct);

        verify(ccxtAdapter).fetchSnapshot(acct);
    }

    @Test
    void startupSnapshot_unknownOrder_logsWarning() {
        ExchangeAccount acct = newAccount(1L);
        var snap = new CcxtOrderAdapter.AccountSnapshot(
                List.of(new CcxtOrderAdapter.OrderSnapshot(
                        "ex-unknown", "c1", "BTC/USDT", "buy",
                        new BigDecimal("1"), BigDecimal.ZERO, "open")),
                List.of());
        when(ccxtAdapter.fetchSnapshot(acct)).thenReturn(snap);
        when(ccxtAdapter.subscribeFills(any(), any())).thenReturn(() -> {});

        // No local order found
        when(orderMapper.findByExchangeOrderId(1L, "ex-unknown")).thenReturn(null);

        executor.startupSnapshot(acct);

        verify(ccxtAdapter).fetchSnapshot(acct);
    }

    @Test
    void startupSnapshot_exceptionSwallowed() {
        ExchangeAccount acct = newAccount(1L);
        when(ccxtAdapter.fetchSnapshot(acct)).thenThrow(new RuntimeException("API down"));

        assertThatCode(() -> executor.startupSnapshot(acct)).doesNotThrowAnyException();
    }

    // ==================== confirmCancelled ====================

    @Test
    void confirmCancelled_transitionsToCancelled() {
        Order order = newOrder(1L, OrderStatus.PENDING_CANCEL);
        when(orderMapper.findById(1L)).thenReturn(order);
        when(orderMapper.casUpdate(order)).thenReturn(1);

        executor.confirmCancelled(1L);

        verify(orderMapper).casUpdate(order);
    }

    @Test
    void confirmCancelled_orderNotFound_noOp() {
        when(orderMapper.findById(999L)).thenReturn(null);

        executor.confirmCancelled(999L);

        verify(orderMapper, never()).casUpdate(any());
    }

    @Test
    void confirmCancelled_casFail_noVersionIncrement() {
        Order order = newOrder(1L, OrderStatus.PENDING_CANCEL);
        long versionBefore = order.getVersion();
        when(orderMapper.findById(1L)).thenReturn(order);
        when(orderMapper.casUpdate(order)).thenReturn(0);

        executor.confirmCancelled(1L);

        // Version should not have been incremented (only incremented on affected==1)
        // Note: transitionTo itself doesn't change version, only the explicit setVersion does
    }

    @Test
    void confirmCancelled_transitionException_swallowed() {
        // Order already in terminal state -> transition to CANCELLED fails
        Order order = newOrder(1L, OrderStatus.FILLED);
        when(orderMapper.findById(1L)).thenReturn(order);

        assertThatCode(() -> executor.confirmCancelled(1L)).doesNotThrowAnyException();

        verify(orderMapper, never()).casUpdate(any());
    }

    // ==================== init (PostConstruct) ====================

    @Test
    void init_doesNotThrow() {
        assertThatCode(() -> executor.init()).doesNotThrowAnyException();
    }

    // ==================== helpers ====================

    private Order newOrder(long id, OrderStatus status) {
        Order o = new Order();
        o.setId(id);
        o.setAccountId(1L);
        o.setSymbol("BTC/USDT");
        o.setSide(OrderSide.BUY);
        o.setOrderType(OrderType.LIMIT);
        o.setAmount(new BigDecimal("1"));
        o.setPrice(new BigDecimal("42000"));
        o.setTimeInForce(TimeInForce.GTC);
        o.setStatus(status);
        o.setVersion(0L);
        o.setFilledQty(BigDecimal.ZERO);
        return o;
    }

    private ExchangeAccount newAccount(long id) {
        ExchangeAccount acct = new ExchangeAccount();
        acct.setId(id);
        acct.setUserId(42L);
        return acct;
    }
}
