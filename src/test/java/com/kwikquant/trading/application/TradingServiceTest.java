package com.kwikquant.trading.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.kwikquant.account.application.ExchangeAccountService;
import com.kwikquant.account.domain.ExchangeAccount;
import com.kwikquant.market.application.TradingPairService;
import com.kwikquant.market.domain.TradingPairInfo;
import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.MarketType;
import com.kwikquant.shared.types.OrderSide;
import com.kwikquant.shared.types.OrderStatus;
import com.kwikquant.shared.types.OrderType;
import com.kwikquant.trading.domain.InvalidOrderException;
import com.kwikquant.trading.domain.Order;
import com.kwikquant.trading.domain.OrderNotFoundException;
import com.kwikquant.trading.domain.OrderSubmitCommand;
import com.kwikquant.trading.domain.TimeInForce;
import com.kwikquant.trading.infrastructure.OrderMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class TradingServiceTest {

    private ExchangeAccountService accountService;
    private TradingPairService pairService;
    private OrderMapper orderMapper;
    private OrderRouter router;
    private Executor executor;
    private TradingService service;

    @BeforeEach
    void setUp() {
        accountService = mock(ExchangeAccountService.class);
        pairService = mock(TradingPairService.class);
        orderMapper = mock(OrderMapper.class);
        router = mock(OrderRouter.class);
        executor = mock(Executor.class);
        service = new TradingService(accountService, pairService, orderMapper, router, new SimpleMeterRegistry());

        // 模拟登录用户
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken("42", "x"));

        ExchangeAccount acct = new ExchangeAccount();
        acct.setId(1L);
        acct.setUserId(42L);
        acct.setExchange(Exchange.BINANCE);
        acct.setPaperTrading(true);
        when(accountService.getOwned(1L, 42L)).thenReturn(acct);

        when(pairService.getPairs(Exchange.BINANCE, MarketType.SPOT))
                .thenReturn(List.of(new TradingPairInfo(
                        Exchange.BINANCE,
                        MarketType.SPOT,
                        "BTC/USDT",
                        "BTC",
                        "USDT",
                        new BigDecimal("0.0001"),
                        new BigDecimal("100"),
                        new BigDecimal("0.01"),
                        new BigDecimal("0.00000001"),
                        true)));

        when(router.route(any(ExchangeAccount.class))).thenReturn(executor);
        // Spring 代理会调 insertOrder（内部事务），test 不走 Spring AOP，直接 mock orderMapper.insert
        doAnswer(invocation -> {
                    Order o = invocation.getArgument(0);
                    o.setId(999L);
                    return null;
                })
                .when(orderMapper)
                .insert(any(Order.class));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void submitInsertsOrderAndRoutesToExecutor() {
        OrderSubmitCommand cmd = new OrderSubmitCommand(
                1L,
                "BTC/USDT",
                MarketType.SPOT,
                OrderSide.BUY,
                OrderType.LIMIT,
                new BigDecimal("0.1"),
                new BigDecimal("42000"),
                null,
                TimeInForce.GTC,
                null,
                "c1");

        OrderSubmitResult result = service.submit(cmd);

        assertThat(result.orderId()).isEqualTo(999L);
        assertThat(result.status()).isEqualTo(OrderStatus.NEW);
        verify(orderMapper).insert(any(Order.class));
        verify(executor).submit(any(Order.class));
    }

    @Test
    void submitRejectsUnknownSymbol() {
        OrderSubmitCommand cmd = new OrderSubmitCommand(
                1L,
                "FOO/BAR",
                MarketType.SPOT,
                OrderSide.BUY,
                OrderType.LIMIT,
                new BigDecimal("0.1"),
                new BigDecimal("42000"),
                null,
                TimeInForce.GTC,
                null,
                "c1");
        assertThatThrownBy(() -> service.submit(cmd))
                .isInstanceOf(InvalidOrderException.class)
                .hasMessageContaining("Unknown symbol");
        verify(orderMapper, never()).insert(any());
        verify(executor, never()).submit(any());
    }

    @Test
    void getOrderRequiresOwnership() {
        Order order = new Order();
        order.setId(50L);
        order.setAccountId(1L);
        when(orderMapper.findById(50L)).thenReturn(order);

        Order loaded = service.getOrder(50L);
        assertThat(loaded.getId()).isEqualTo(50L);
        verify(accountService).getOwned(1L, 42L);
    }

    @Test
    void getOrderThrowsWhenNotFound() {
        when(orderMapper.findById(123L)).thenReturn(null);
        assertThatThrownBy(() -> service.getOrder(123L)).isInstanceOf(OrderNotFoundException.class);
    }

    @Test
    void cancelTransitionsToPendingCancel() {
        Order order = new Order();
        order.setId(60L);
        order.setAccountId(1L);
        order.setStatus(OrderStatus.SUBMITTED);
        order.setVersion(2L);
        when(orderMapper.findById(60L)).thenReturn(order);
        when(orderMapper.casUpdate(order)).thenReturn(1);

        OrderCancelResult result = service.cancel(60L);
        assertThat(result.status()).isEqualTo(OrderStatus.PENDING_CANCEL);
        verify(executor).cancel(any(Order.class));
    }
}
