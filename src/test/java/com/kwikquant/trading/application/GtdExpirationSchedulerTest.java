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
    private TradingTransactionHelper txHelper;
    private GtdExpirationScheduler scheduler;

    @BeforeEach
    void setUp() {
        orderMapper = mock(OrderMapper.class);
        wsBroadcaster = mock(OrderWebSocketBroadcaster.class);
        accountService = mock(ExchangeAccountService.class);
        txHelper = mock(TradingTransactionHelper.class);
        scheduler = new GtdExpirationScheduler(orderMapper, wsBroadcaster, accountService, txHelper);
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
        verify(txHelper, never()).unfreezeBalance(any(), any());
    }

    /**
     * GTD 过期从未走 cancel/fill，此前一直漏做释放冻结额，导致模拟盘账户的 used 余额永久卡死。
     */
    @Test
    void scan_paperAccount_unfreezesReservedBalance() {
        Order expired = order(1L, OrderStatus.SUBMITTED);
        when(orderMapper.findExpiredGtd(any(Instant.class))).thenReturn(List.of(expired));
        when(orderMapper.casUpdate(any())).thenReturn(1);
        ExchangeAccount acct = new ExchangeAccount();
        acct.setUserId(42L);
        acct.setPaperTrading(true);
        when(accountService.findById(1L)).thenReturn(acct);

        scheduler.scan();

        verify(txHelper).unfreezeBalance(expired, acct);
    }

    @Test
    void scan_liveAccount_doesNotUnfreeze() {
        Order expired = order(1L, OrderStatus.SUBMITTED);
        when(orderMapper.findExpiredGtd(any(Instant.class))).thenReturn(List.of(expired));
        when(orderMapper.casUpdate(any())).thenReturn(1);
        ExchangeAccount acct = new ExchangeAccount();
        acct.setUserId(42L);
        acct.setPaperTrading(false);
        when(accountService.findById(1L)).thenReturn(acct);

        scheduler.scan();

        verify(txHelper, never()).unfreezeBalance(any(), any());
    }

    @Test
    void scan_unfreezeThrows_doesNotBlockExpiry() {
        Order expired = order(1L, OrderStatus.SUBMITTED);
        when(orderMapper.findExpiredGtd(any(Instant.class))).thenReturn(List.of(expired));
        when(orderMapper.casUpdate(any())).thenReturn(1);
        ExchangeAccount acct = new ExchangeAccount();
        acct.setUserId(42L);
        acct.setPaperTrading(true);
        when(accountService.findById(1L)).thenReturn(acct);
        doThrow(new RuntimeException("db down")).when(txHelper).unfreezeBalance(any(), any());

        scheduler.scan();

        // 解冻失败不该阻断过期状态的推进/后续订单处理
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
