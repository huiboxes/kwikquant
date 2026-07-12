package com.kwikquant.trading.interfaces;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.kwikquant.account.application.ExchangeAccountService;
import com.kwikquant.account.domain.ExchangeAccount;
import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.OrderSide;
import com.kwikquant.shared.types.OrderStatus;
import com.kwikquant.shared.types.OrderType;
import com.kwikquant.trading.application.OrderCancelResult;
import com.kwikquant.trading.application.OrderSubmitResult;
import com.kwikquant.trading.application.TradingService;
import com.kwikquant.trading.domain.Fill;
import com.kwikquant.trading.domain.InvalidOrderException;
import com.kwikquant.trading.domain.Order;
import com.kwikquant.trading.domain.OrderNotFoundException;
import com.kwikquant.trading.domain.OrderSubmitCommand;
import com.kwikquant.trading.domain.TimeInForce;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Unit tests for {@link OrderController}.
 *
 * <p>Pure Mockito style (consistent with ControllerAuthTest / RiskPolicyControllerTest).
 * Covers all public endpoints: submit, getOne, list, cancel, listFills.
 */
class OrderControllerTest {

    private TradingService tradingService;

    private ExchangeAccountService accountService;
    private OrderController controller;

    @BeforeEach
    void setUp() {
        tradingService = mock(TradingService.class);

        accountService = mock(ExchangeAccountService.class);
        controller = new OrderController(tradingService, accountService);

        // Simulate authenticated user id=42
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("42", "x"));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ---- submit ----

    @Test
    void submit_whenValid_returnsCreatedResult() {
        OrderSubmitRequest req = new OrderSubmitRequest(
                1L,
                "BTC/USDT",
                "buy",
                "limit",
                new BigDecimal("0.5"),
                new BigDecimal("30000"),
                null,
                "GTC",
                null,
                "client-001",
                "SPOT");

        OrderSubmitResult expected = new OrderSubmitResult(100L, OrderStatus.NEW, 1L, Instant.now());
        when(tradingService.submit(any(OrderSubmitCommand.class))).thenReturn(expected);

        var response = controller.submit(req, mock(HttpServletRequest.class));

        assertThat(response.code()).isEqualTo(0);
        assertThat(response.data().orderId()).isEqualTo(100L);
        assertThat(response.data().status()).isEqualTo(OrderStatus.NEW);
        verify(tradingService).submit(any(OrderSubmitCommand.class));
    }

    @Test
    void submit_whenInvalidEnum_throwsInvalidOrderException() {
        OrderSubmitRequest req = new OrderSubmitRequest(
                1L,
                "BTC/USDT",
                "buy",
                "BOGUS_TYPE",
                new BigDecimal("0.5"),
                new BigDecimal("30000"),
                null,
                null,
                null,
                null,
                "SPOT");

        assertThatThrownBy(() -> controller.submit(req, mock(HttpServletRequest.class)))
                .isInstanceOf(InvalidOrderException.class)
                .hasMessageContaining("Invalid enum value");
    }

    @Test
    void submit_withExpireAt_parsesCorrectly() {
        String expireAt = "2026-12-31T23:59:59Z";
        OrderSubmitRequest req = new OrderSubmitRequest(
                1L,
                "ETH/USDT",
                "sell",
                "limit",
                new BigDecimal("10"),
                new BigDecimal("2000"),
                null,
                "IOC",
                expireAt,
                "client-002",
                "SPOT");

        OrderSubmitResult expected = new OrderSubmitResult(101L, OrderStatus.NEW, 1L, Instant.now());
        when(tradingService.submit(any(OrderSubmitCommand.class))).thenReturn(expected);

        var response = controller.submit(req, mock(HttpServletRequest.class));

        assertThat(response.data().orderId()).isEqualTo(101L);
        verify(tradingService).submit(argThat(cmd -> cmd.expireAt() != null && cmd.timeInForce() == TimeInForce.IOC));
    }

    @Test
    void submit_workerScenario_overridesAccountIdFromTokenAttr() {
        // Round-6 BLOCKER 1/MAJOR:Worker 场景通过 WorkerTokenFilter attr 推导 account,覆盖 request.accountId
        HttpServletRequest httpReq = mock(HttpServletRequest.class);
        when(httpReq.getAttribute("workerStrategyId")).thenReturn(7L);
        when(httpReq.getAttribute("workerUserId")).thenReturn(42L);
        when(httpReq.getAttribute("workerExchange")).thenReturn("BINANCE");

        ExchangeAccount derived = new ExchangeAccount();
        derived.setId(999L);
        derived.setUserId(42L);
        derived.setExchange(Exchange.BINANCE);
        when(accountService.findByUserAndExchange(42L, "BINANCE")).thenReturn(derived);

        OrderSubmitRequest req = new OrderSubmitRequest(
                null, // Worker 不传 accountId
                "BTC/USDT",
                "buy",
                "market",
                new BigDecimal("0.1"),
                null,
                null,
                "GTC",
                null,
                "cli-worker",
                "SPOT");
        OrderSubmitResult ok = new OrderSubmitResult(500L, OrderStatus.NEW, 999L, Instant.now());
        when(tradingService.submit(any(OrderSubmitCommand.class))).thenReturn(ok);

        controller.submit(req, httpReq);

        // 关键断言:cmd.accountId 被 derived.id 覆盖,不是 request.accountId (null)
        verify(tradingService).submit(argThat(cmd -> cmd.accountId() == 999L));
    }

    @Test
    void submit_workerScenario_noAccountForUserExchange_throwsInvalidOrder() {
        // Round-6 BLOCKER 2 补测试:workerStrategyId 存在但 findByUserAndExchange 返回 null → 抛 InvalidOrderException
        HttpServletRequest httpReq = mock(HttpServletRequest.class);
        when(httpReq.getAttribute("workerStrategyId")).thenReturn(7L);
        when(httpReq.getAttribute("workerUserId")).thenReturn(42L);
        when(httpReq.getAttribute("workerExchange")).thenReturn("KRAKEN");
        when(accountService.findByUserAndExchange(42L, "KRAKEN")).thenReturn(null);

        OrderSubmitRequest req = new OrderSubmitRequest(
                null, "BTC/USDT", "buy", "market", new BigDecimal("0.1"), null, null, "GTC", null, null, "SPOT");

        assertThatThrownBy(() -> controller.submit(req, httpReq))
                .isInstanceOf(InvalidOrderException.class)
                .hasMessageContaining("no exchange account");
        verify(tradingService, never()).submit(any());
    }

    @Test
    void submit_userScenarioMissingAccountId_throwsInvalidOrder() {
        // user 请求(无 workerStrategyId attr)必须提供 accountId
        HttpServletRequest httpReq = mock(HttpServletRequest.class);
        // no worker attrs
        OrderSubmitRequest req = new OrderSubmitRequest(
                null, "BTC/USDT", "buy", "market", new BigDecimal("0.1"), null, null, "GTC", null, null, "SPOT");

        assertThatThrownBy(() -> controller.submit(req, httpReq))
                .isInstanceOf(InvalidOrderException.class)
                .hasMessageContaining("accountId required");
    }

    // ---- getOne ----

    @Test
    void getOne_whenOwner_returnsOrder() {
        Order order = sampleOrder(100L, 1L, "BTC/USDT");
        when(tradingService.getOrder(100L)).thenReturn(order);

        var response = controller.getOne(100L);

        assertThat(response.code()).isEqualTo(0);
        assertThat(response.data().orderId()).isEqualTo(100L);
        assertThat(response.data().symbol()).isEqualTo("BTC/USDT");
        assertThat(response.data().side()).isEqualTo("buy");
        assertThat(response.data().orderType()).isEqualTo("limit");
        verify(tradingService).getOrder(100L);
    }

    @Test
    void getOne_whenNotOwner_throwsOrderNotFound() {
        // TradingService.getOrder throws OrderNotFoundException for both not-found and not-owner
        when(tradingService.getOrder(999L)).thenThrow(new OrderNotFoundException(999L));

        assertThatThrownBy(() -> controller.getOne(999L)).isInstanceOf(OrderNotFoundException.class);
    }

    // ---- list ----

    @Test
    void list_whenOwner_returnsPage() {
        ExchangeAccount acct = new ExchangeAccount();
        acct.setId(1L);
        acct.setUserId(42L);
        acct.setExchange(Exchange.BINANCE);
        when(accountService.getOwned(1L, 42L)).thenReturn(acct);

        List<Order> orders = List.of(sampleOrder(100L, 1L, "BTC/USDT"), sampleOrder(101L, 1L, "ETH/USDT"));
        when(tradingService.queryOrders(eq(1L), isNull(), isNull(), isNull(), isNull(), eq(50), eq(0)))
                .thenReturn(orders);
        when(tradingService.countOrders(eq(1L), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(2L);

        OrderListQuery query = new OrderListQuery(1L, null, null, null, null, null, null);
        var response = controller.list(query);

        assertThat(response.code()).isEqualTo(0);
        assertThat(response.data().content()).hasSize(2);
        assertThat(response.data().total()).isEqualTo(2L);
        assertThat(response.data().page()).isEqualTo(1);
        assertThat(response.data().pageSize()).isEqualTo(50);
        verify(accountService).getOwned(1L, 42L);
    }

    @Test
    void list_withStatusFilter_parsesStatuses() {
        ExchangeAccount acct = new ExchangeAccount();
        acct.setId(1L);
        acct.setUserId(42L);
        acct.setExchange(Exchange.BINANCE);
        when(accountService.getOwned(1L, 42L)).thenReturn(acct);

        when(tradingService.queryOrders(eq(1L), eq("BTC/USDT"), anyList(), isNull(), isNull(), eq(20), eq(0)))
                .thenReturn(List.of(sampleOrder(100L, 1L, "BTC/USDT")));
        when(tradingService.countOrders(eq(1L), eq("BTC/USDT"), anyList(), isNull(), isNull()))
                .thenReturn(1L);

        OrderListQuery query = new OrderListQuery(1L, "BTC/USDT", "NEW,FILLED", null, null, 1, 20);
        var response = controller.list(query);

        assertThat(response.data().content()).hasSize(1);
        assertThat(response.data().totalPages()).isEqualTo(1);
    }

    @Test
    void list_withTimeRange_parsesDates() {
        ExchangeAccount acct = new ExchangeAccount();
        acct.setId(1L);
        acct.setUserId(42L);
        acct.setExchange(Exchange.BINANCE);
        when(accountService.getOwned(1L, 42L)).thenReturn(acct);

        String start = "2026-01-01T00:00:00Z";
        String end = "2026-06-30T23:59:59Z";

        when(tradingService.queryOrders(
                        eq(1L), isNull(), isNull(), any(Instant.class), any(Instant.class), eq(50), eq(0)))
                .thenReturn(List.of());
        when(tradingService.countOrders(eq(1L), isNull(), isNull(), any(Instant.class), any(Instant.class)))
                .thenReturn(0L);

        OrderListQuery query = new OrderListQuery(1L, null, null, start, end, null, null);
        var response = controller.list(query);

        assertThat(response.data().content()).isEmpty();
        assertThat(response.data().total()).isEqualTo(0L);
    }

    @Test
    void list_withInvalidDateFormat_throwsInvalidOrderException() {
        ExchangeAccount acct = new ExchangeAccount();
        acct.setId(1L);
        acct.setUserId(42L);
        acct.setExchange(Exchange.BINANCE);
        when(accountService.getOwned(1L, 42L)).thenReturn(acct);

        OrderListQuery query = new OrderListQuery(1L, null, null, "not-a-date", null, null, null);

        assertThatThrownBy(() -> controller.list(query))
                .isInstanceOf(InvalidOrderException.class)
                .hasMessageContaining("Invalid date format");
    }

    @Test
    void list_withInvalidStatus_throwsInvalidOrderException() {
        ExchangeAccount acct = new ExchangeAccount();
        acct.setId(1L);
        acct.setUserId(42L);
        acct.setExchange(Exchange.BINANCE);
        when(accountService.getOwned(1L, 42L)).thenReturn(acct);

        OrderListQuery query = new OrderListQuery(1L, null, "BOGUS_STATUS", null, null, null, null);

        assertThatThrownBy(() -> controller.list(query))
                .isInstanceOf(InvalidOrderException.class)
                .hasMessageContaining("Invalid status value");
    }

    // ---- cancel ----

    @Test
    void cancel_whenOwner_returnsResult() {
        OrderCancelResult expected = new OrderCancelResult(100L, OrderStatus.PENDING_CANCEL, 2L);
        when(tradingService.cancel(100L)).thenReturn(expected);

        var response = controller.cancel(100L);

        assertThat(response.code()).isEqualTo(0);
        assertThat(response.data().orderId()).isEqualTo(100L);
        assertThat(response.data().status()).isEqualTo(OrderStatus.PENDING_CANCEL);
        verify(tradingService).cancel(100L);
    }

    // ---- listFills ----

    @Test
    void listFills_whenOwner_returnsFills() {
        Order order = sampleOrder(100L, 1L, "BTC/USDT");
        when(tradingService.getOrder(100L)).thenReturn(order);

        Fill fill = sampleFill(1L, 100L, 1L, "BTC/USDT");
        when(tradingService.listFillsByOrder(100L)).thenReturn(List.of(fill));

        var response = controller.listFills(100L);

        assertThat(response.code()).isEqualTo(0);
        assertThat(response.data()).hasSize(1);
        assertThat(response.data().getFirst().fillId()).isEqualTo(1L);
        assertThat(response.data().getFirst().symbol()).isEqualTo("BTC/USDT");
        assertThat(response.data().getFirst().side()).isEqualTo("buy");
        verify(tradingService).getOrder(100L);
        verify(tradingService).listFillsByOrder(100L);
    }

    @Test
    void listFills_whenEmpty_returnsEmptyList() {
        Order order = sampleOrder(100L, 1L, "BTC/USDT");
        when(tradingService.getOrder(100L)).thenReturn(order);
        when(tradingService.listFillsByOrder(100L)).thenReturn(List.of());

        var response = controller.listFills(100L);

        assertThat(response.data()).isEmpty();
    }

    // ---- helpers ----

    private Order sampleOrder(long id, long accountId, String symbol) {
        Order o = new Order();
        o.setId(id);
        o.setAccountId(accountId);
        o.setSymbol(symbol);
        o.setSide(OrderSide.BUY);
        o.setOrderType(OrderType.LIMIT);
        o.setAmount(new BigDecimal("0.5"));
        o.setPrice(new BigDecimal("30000"));
        o.setStatus(OrderStatus.NEW);
        o.setFilledQty(BigDecimal.ZERO);
        o.setVersion(1L);
        o.setCreatedAt(Instant.now());
        o.setUpdatedAt(Instant.now());
        return o;
    }

    private Fill sampleFill(long id, long orderId, long accountId, String symbol) {
        Fill f = new Fill();
        f.setId(id);
        f.setOrderId(orderId);
        f.setAccountId(accountId);
        f.setSymbol(symbol);
        f.setSide(OrderSide.BUY);
        f.setPrice(new BigDecimal("30000"));
        f.setQty(new BigDecimal("0.5"));
        f.setFee(new BigDecimal("0.001"));
        f.setFeeCurrency("BTC");
        f.setLiquidity("taker");
        f.setExternalFillId("ext-001");
        f.setFilledAt(Instant.now());
        return f;
    }
}
