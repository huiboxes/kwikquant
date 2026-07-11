package com.kwikquant.trading.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.kwikquant.account.application.BalanceService;
import com.kwikquant.account.application.ExchangeAccountService;
import com.kwikquant.account.domain.ExchangeAccount;
import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.OrderSide;
import com.kwikquant.shared.types.OrderStatus;
import com.kwikquant.trading.domain.Order;
import com.kwikquant.trading.infrastructure.ConcurrencyConflictException;
import com.kwikquant.trading.infrastructure.FillMapper;
import com.kwikquant.trading.infrastructure.OrderMapper;
import com.kwikquant.trading.interfaces.OrderWebSocketBroadcaster;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class ExecutionServiceUnitTest {
    private OrderMapper orderMapper;
    private FillMapper fillMapper;
    private PositionService positionService;
    private OrderWebSocketBroadcaster wsBroadcaster;
    private ExchangeAccountService accountService;
    private BalanceService balanceService;
    private ExecutionService service;

    @BeforeEach
    void setUp() {
        orderMapper = mock(OrderMapper.class);
        fillMapper = mock(FillMapper.class);
        positionService = mock(PositionService.class);
        wsBroadcaster = mock(OrderWebSocketBroadcaster.class);
        accountService = mock(ExchangeAccountService.class);
        balanceService = mock(BalanceService.class);
        service = new ExecutionService(
                orderMapper,
                fillMapper,
                positionService,
                wsBroadcaster,
                accountService,
                new SimpleMeterRegistry(),
                balanceService);
    }

    @Test
    void processExecutionReport_whenOrderNotFound_throws() {
        when(orderMapper.findById(99L)).thenReturn(null);
        ExecutionReport report = report(99L, "fill-1");
        assertThatThrownBy(() -> service.processExecutionReport(report))
                .isInstanceOf(com.kwikquant.trading.domain.OrderNotFoundException.class);
    }

    @Test
    void processExecutionReport_whenIdempotent_skipsProcessing() {
        Order order = order(1L, OrderStatus.SUBMITTED);
        when(orderMapper.findById(1L)).thenReturn(order);
        when(fillMapper.existsByExternalFillId(1L, "fill-1")).thenReturn(true);

        service.processExecutionReport(report(1L, "fill-1"));

        verify(orderMapper, never()).casUpdate(any());
    }

    @Test
    void processExecutionReport_whenTerminalStatus_skipsProcessing() {
        Order order = order(1L, OrderStatus.FILLED);
        when(orderMapper.findById(1L)).thenReturn(order);

        service.processExecutionReport(report(1L, "fill-1"));

        verify(orderMapper, never()).casUpdate(any());
    }

    // Note: successfulFill, onExchangeAccepted, onExchangeRejected require active transaction
    // context (TransactionSynchronizationManager) for WS broadcast. These paths are covered
    // by ExecutionServiceIntegrationTest (@SpringBootTest). This unit test covers early-return
    // and error paths that don't reach the broadcast code.

    @Test
    void processExecutionReport_casConflictRetries_exhaustedThrows() {
        // Each retry re-reads the order; return a fresh copy so accumulateFill doesn't over-fill
        when(orderMapper.findById(1L)).thenAnswer(inv -> order(1L, OrderStatus.SUBMITTED));
        when(orderMapper.casUpdate(any())).thenReturn(0);

        ExecutionReport rpt = report(1L, "fill-1");
        assertThatThrownBy(() -> service.processExecutionReport(rpt))
                .isInstanceOf(ConcurrencyConflictException.class)
                .hasMessageContaining("3 retries");
    }

    /**
     * 成交回报处理成功后,调 balanceService.applyFill(同事务 REQUIRED,保证余额扣减 + 持仓 +
     * 订单推进 + Fill insert 原子)。paperTrading 取自 accountService.findById(复用 userId 查询,避免额外 DB 调用)。
     *
     * <p>需手动 init TransactionSynchronizationManager(registerSynchronization 要求活跃同步上下文;
     * 成功路径在 ExecutionServiceIntegrationTest 也覆盖,本单元测试专注 applyFill wiring)。
     */
    @Test
    void processExecutionReport_success_callsBalanceApplyFillWithPaperTradingFlag() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            Order order = order(1L, OrderStatus.SUBMITTED);
            when(orderMapper.findById(1L)).thenReturn(order);
            when(orderMapper.casUpdate(any())).thenReturn(1);
            when(fillMapper.existsByExternalFillId(1L, "fill-1")).thenReturn(false);
            ExchangeAccount acct = new ExchangeAccount();
            acct.setId(1L);
            acct.setUserId(42L);
            acct.setExchange(Exchange.BINANCE);
            acct.setPaperTrading(true);
            when(accountService.findById(1L)).thenReturn(acct);

            service.processExecutionReport(report(1L, "fill-1"));

            // applyFill 用 account.isPaperTrading(),非 order 的字段
            verify(balanceService)
                    .applyFill(eq(1L), eq(true), eq(OrderSide.BUY), eq("BTC/USDT"), any(), any(), any(), any());
            verify(positionService).applyFill(eq(1L), eq("BTC/USDT"), eq(OrderSide.BUY), any(), any(), any());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private Order order(long id, OrderStatus status) {
        Order o = new Order();
        o.setId(id);
        o.setAccountId(1L);
        o.setSymbol("BTC/USDT");
        o.setSide(OrderSide.BUY);
        o.setStatus(status);
        o.setVersion(1L);
        o.setAmount(new BigDecimal("1"));
        o.setFilledQty(BigDecimal.ZERO);
        return o;
    }

    private ExecutionReport report(long orderId, String externalFillId) {
        return new ExecutionReport(
                orderId,
                externalFillId,
                new BigDecimal("40000"),
                new BigDecimal("1"),
                new BigDecimal("0.1"),
                "USDT",
                "taker",
                Instant.now());
    }
}
