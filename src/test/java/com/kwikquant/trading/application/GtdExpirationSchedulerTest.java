package com.kwikquant.trading.application;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.kwikquant.account.application.ExchangeAccountService;
import com.kwikquant.account.domain.ExchangeAccount;
import com.kwikquant.shared.types.OrderStatus;
import com.kwikquant.trading.domain.Order;
import com.kwikquant.trading.infrastructure.OrderMapper;
import com.kwikquant.trading.interfaces.OrderWebSocketBroadcaster;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GtdExpirationSchedulerTest {
    private OrderMapper orderMapper;
    private OrderWebSocketBroadcaster wsBroadcaster;
    private ExchangeAccountService accountService;
    private GtdExpirationScheduler scheduler;

    @BeforeEach
    void setUp() {
        orderMapper = mock(OrderMapper.class);
        wsBroadcaster = mock(OrderWebSocketBroadcaster.class);
        accountService = mock(ExchangeAccountService.class);
        scheduler = new GtdExpirationScheduler(orderMapper, wsBroadcaster, accountService);
    }

    @Test
    void scan_whenNoExpiredOrders_noOp() {
        when(orderMapper.findExpiredGtd(any(Instant.class))).thenReturn(List.of());
        scheduler.scan();
        verify(orderMapper, never()).casUpdate(any());
    }

    @Test
    void scan_whenExpiredOrders_transitionsToExpired() {
        Order expired = order(1L, OrderStatus.SUBMITTED);
        when(orderMapper.findExpiredGtd(any(Instant.class))).thenReturn(List.of(expired));
        when(orderMapper.casUpdate(any())).thenReturn(1);
        ExchangeAccount acct = new ExchangeAccount();
        acct.setUserId(42L);
        when(accountService.findById(1L)).thenReturn(acct);
        scheduler.scan();
        verify(orderMapper).casUpdate(any());
    }

    @Test
    void scan_whenCasFails_skipsOrder() {
        Order expired = order(1L, OrderStatus.SUBMITTED);
        when(orderMapper.findExpiredGtd(any(Instant.class))).thenReturn(List.of(expired));
        when(orderMapper.casUpdate(any())).thenReturn(0);
        scheduler.scan();
        verify(wsBroadcaster, never()).broadcast(anyLong(), any(com.kwikquant.trading.interfaces.OrderEvent.class));
    }

    @Test
    void scan_whenTransitionThrows_skipsAndContinues() {
        Order bad = order(1L, OrderStatus.FILLED);
        Order good = order(2L, OrderStatus.SUBMITTED);
        when(orderMapper.findExpiredGtd(any(Instant.class))).thenReturn(List.of(bad, good));
        when(orderMapper.casUpdate(any())).thenReturn(1);
        ExchangeAccount acct = new ExchangeAccount();
        acct.setUserId(42L);
        when(accountService.findById(anyLong())).thenReturn(acct);
        scheduler.scan();
        verify(orderMapper, times(1)).casUpdate(any());
    }

    @Test
    void scan_whenAccountNotFound_usesZeroUserId() {
        Order expired = order(1L, OrderStatus.SUBMITTED);
        when(orderMapper.findExpiredGtd(any(Instant.class))).thenReturn(List.of(expired));
        when(orderMapper.casUpdate(any())).thenReturn(1);
        when(accountService.findById(1L)).thenReturn(null);
        scheduler.scan();
        verify(orderMapper).casUpdate(any());
    }

    private Order order(long id, OrderStatus status) {
        Order o = new Order();
        o.setId(id);
        o.setAccountId(1L);
        o.setSymbol("BTC/USDT");
        o.setStatus(status);
        o.setVersion(1L);
        return o;
    }
}
