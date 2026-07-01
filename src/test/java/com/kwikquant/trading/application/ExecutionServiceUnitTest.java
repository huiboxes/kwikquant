package com.kwikquant.trading.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.kwikquant.account.application.ExchangeAccountService;
import com.kwikquant.account.domain.ExchangeAccount;
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

class ExecutionServiceUnitTest {
    private OrderMapper orderMapper;
    private FillMapper fillMapper;
    private PositionService positionService;
    private OrderWebSocketBroadcaster wsBroadcaster;
    private ExchangeAccountService accountService;
    private ExecutionService service;

    @BeforeEach
    void setUp() {
        orderMapper = mock(OrderMapper.class);
        fillMapper = mock(FillMapper.class);
        positionService = mock(PositionService.class);
        wsBroadcaster = mock(OrderWebSocketBroadcaster.class);
        accountService = mock(ExchangeAccountService.class);
        service = new ExecutionService(orderMapper, fillMapper, positionService, wsBroadcaster, accountService, new SimpleMeterRegistry());
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
        return new ExecutionReport(orderId, externalFillId,
                new BigDecimal("40000"), new BigDecimal("1"),
                new BigDecimal("0.1"), "USDT", "taker", Instant.now());
    }
}
