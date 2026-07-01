package com.kwikquant.trading.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.kwikquant.account.application.ExchangeAccountService;
import com.kwikquant.account.domain.ExchangeAccount;
import com.kwikquant.market.application.MarketDataService;
import com.kwikquant.market.application.TradingPairService;
import com.kwikquant.market.domain.TradingPairInfo;
import com.kwikquant.risk.application.RiskService;
import com.kwikquant.risk.domain.RiskCheckRequest;
import com.kwikquant.risk.domain.RiskDecision;
import com.kwikquant.risk.domain.RiskRejectedException;
import com.kwikquant.risk.domain.RiskVerdict;
import com.kwikquant.risk.domain.RuleResult;
import com.kwikquant.risk.domain.RiskRuleType;
import com.kwikquant.shared.infra.AuditEntry;
import com.kwikquant.shared.infra.AuditRepository;
import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.MarketType;
import com.kwikquant.shared.types.OrderSide;
import com.kwikquant.shared.types.OrderStatus;
import com.kwikquant.shared.types.OrderType;
import com.kwikquant.shared.types.RiskTriggeredEvent;
import com.kwikquant.trading.domain.InvalidOrderException;
import com.kwikquant.trading.domain.Order;
import com.kwikquant.trading.domain.OrderNotFoundException;
import com.kwikquant.trading.domain.OrderSubmitCommand;
import com.kwikquant.trading.domain.TimeInForce;
import com.kwikquant.trading.infrastructure.OrderMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class TradingServiceTest {

    private ExchangeAccountService accountService;
    private TradingPairService pairService;
    private OrderMapper orderMapper;
    private OrderRouter router;
    private Executor executor;
    private RiskService riskService;
    private MarketDataService marketDataService;
    private AuditRepository auditRepository;
    private ApplicationEventPublisher publisher;
    private TradingService service;

    @BeforeEach
    void setUp() {
        accountService = mock(ExchangeAccountService.class);
        pairService = mock(TradingPairService.class);
        orderMapper = mock(OrderMapper.class);
        router = mock(OrderRouter.class);
        executor = mock(Executor.class);
        riskService = mock(RiskService.class);
        marketDataService = mock(MarketDataService.class);
        auditRepository = mock(AuditRepository.class);
        publisher = mock(ApplicationEventPublisher.class);
        service = new TradingService(
                accountService, pairService, orderMapper, router,
                riskService, marketDataService, auditRepository, publisher,
                new SimpleMeterRegistry());

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
        // CAS update succeeds by default (affected=1); tests needing the conflict path
        // (affected=0 → re-read) override this stub.
        when(orderMapper.casUpdate(any(Order.class))).thenReturn(1);

        // Default: risk check approves
        RiskDecision approvedDecision = new RiskDecision();
        approvedDecision.setVerdict(RiskVerdict.APPROVED);
        approvedDecision.setRuleResults(List.of());
        when(riskService.check(any(RiskCheckRequest.class))).thenReturn(approvedDecision);
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
        verify(riskService).check(any(RiskCheckRequest.class));
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
    void submitRejectsWhenRiskCheckRejects() {
        RiskDecision rejectedDecision = new RiskDecision();
        rejectedDecision.setVerdict(RiskVerdict.REJECTED);
        rejectedDecision.setRuleResults(List.of(
                new RuleResult(RiskRuleType.MAX_NOTIONAL, false, "notional exceeds limit")));
        when(riskService.check(any(RiskCheckRequest.class))).thenReturn(rejectedDecision);

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

        // P1-2: rule rejection throws RiskRejectedException (→ HTTP 200 + 4105
        // ORDER_RISK_REJECTED), consistent with the service-unavailable rejection path so
        // the frontend uses one code for all risk rejections.
        assertThatThrownBy(() -> service.submit(cmd))
                .isInstanceOf(RiskRejectedException.class)
                .hasMessageContaining("MAX_NOTIONAL");

        verify(orderMapper).casUpdate(any(Order.class));
        verify(executor, never()).submit(any());

        // Verify RiskTriggeredEvent was published
        ArgumentCaptor<RiskTriggeredEvent> eventCaptor = ArgumentCaptor.forClass(RiskTriggeredEvent.class);
        verify(publisher).publishEvent(eventCaptor.capture());
        RiskTriggeredEvent event = eventCaptor.getValue();
        assertThat(event.userId()).isEqualTo(42L);
        // P1-1: event carries orderId so the frontend can correlate the rejection notification
        assertThat(event.orderId().value()).isEqualTo(999L);
        assertThat(event.accountId().value()).isEqualTo(1L);
        assertThat(event.strategyId()).isNull();
        // M6: rejectionSummary carries only rule type names — no thresholds leak to WS push
        assertThat(event.reason()).contains("MAX_NOTIONAL");
        assertThat(event.reason()).doesNotContain("notional exceeds limit");
    }

    @Test
    void submitBypassesRiskForPositionReducingSellOnServiceFailure() {
        when(riskService.check(any(RiskCheckRequest.class)))
                .thenThrow(new RuntimeException("risk service down"));

        OrderSubmitCommand cmd = new OrderSubmitCommand(
                1L,
                "BTC/USDT",
                MarketType.SPOT,
                OrderSide.SELL,
                OrderType.STOP_MARKET,
                new BigDecimal("0.1"),
                null,
                new BigDecimal("40000"),
                TimeInForce.GTC,
                null,
                "c1");

        OrderSubmitResult result = service.submit(cmd);

        // Should bypass risk and proceed to executor
        assertThat(result.orderId()).isEqualTo(999L);
        verify(executor).submit(any(Order.class));
        // H4: RISK_BYPASSED must be persisted to audit_logs (fire-and-forget; audit failure
        // must not block the bypass).
        ArgumentCaptor<AuditEntry> auditCaptor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditRepository).save(auditCaptor.capture());
        AuditEntry audit = auditCaptor.getValue();
        assertThat(audit.action()).isEqualTo("RISK_BYPASSED");
        assertThat(audit.targetType()).isEqualTo("ORDER");
        assertThat(audit.targetId()).isEqualTo("999");
    }

    @Test
    void submitRejectsNonPositionReducingOrderOnRiskServiceFailure() {
        when(riskService.check(any(RiskCheckRequest.class)))
                .thenThrow(new RuntimeException("risk service down"));

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

        assertThatThrownBy(() -> service.submit(cmd))
                .isInstanceOf(RiskRejectedException.class)
                .hasMessageContaining("risk service unavailable");
        verify(orderMapper).casUpdate(any(Order.class));
        verify(executor, never()).submit(any());
    }

    @Test
    void submitBypass_whenAuditSaveFails_stillProceeds() {
        when(riskService.check(any(RiskCheckRequest.class)))
                .thenThrow(new RuntimeException("risk service down"));
        doThrow(new RuntimeException("audit DB down")).when(auditRepository).save(any(AuditEntry.class));

        OrderSubmitCommand cmd = new OrderSubmitCommand(
                1L, "BTC/USDT", MarketType.SPOT, OrderSide.SELL, OrderType.STOP_MARKET,
                new BigDecimal("0.1"), null, new BigDecimal("40000"), TimeInForce.GTC, null, "c1");

        // Audit failure must NOT block the bypass — order still proceeds to executor
        OrderSubmitResult result = service.submit(cmd);
        assertThat(result.orderId()).isEqualTo(999L);
        verify(executor).submit(any(Order.class));
    }

    @Test
    void submitNonPositionReducing_whenCasUpdateFails_returnsLatestState() {
        when(riskService.check(any(RiskCheckRequest.class)))
                .thenThrow(new RuntimeException("risk service down"));
        // casUpdate returns 0 → concurrent transition → re-read latest
        when(orderMapper.casUpdate(any(Order.class))).thenReturn(0);
        Order latest = new Order();
        latest.setId(999L);
        latest.setAccountId(1L);
        latest.setStatus(OrderStatus.SUBMITTED); // another thread already moved it
        latest.setVersion(2L);
        latest.setCreatedAt(Instant.now());
        when(orderMapper.findById(999L)).thenReturn(latest);

        OrderSubmitCommand cmd = new OrderSubmitCommand(
                1L, "BTC/USDT", MarketType.SPOT, OrderSide.BUY, OrderType.LIMIT,
                new BigDecimal("0.1"), new BigDecimal("42000"), null, TimeInForce.GTC, null, "c1");

        OrderSubmitResult result = service.submit(cmd);
        // Should return latest state instead of throwing
        assertThat(result.status()).isEqualTo(OrderStatus.SUBMITTED);
        verify(executor, never()).submit(any());
    }

    @Test
    void submitRiskRejected_whenCasUpdateFails_returnsLatestState() {
        RiskDecision rejectedDecision = new RiskDecision();
        rejectedDecision.setVerdict(RiskVerdict.REJECTED);
        rejectedDecision.setRuleResults(List.of(new RuleResult(RiskRuleType.MAX_NOTIONAL, false, "exceeds")));
        when(riskService.check(any(RiskCheckRequest.class))).thenReturn(rejectedDecision);
        // REJECTED branch casUpdate returns 0 → concurrent transition
        when(orderMapper.casUpdate(any(Order.class))).thenReturn(0);
        Order latest = new Order();
        latest.setId(999L);
        latest.setAccountId(1L);
        latest.setStatus(OrderStatus.FILLED); // another thread filled it
        latest.setVersion(2L);
        latest.setCreatedAt(Instant.now());
        when(orderMapper.findById(999L)).thenReturn(latest);

        OrderSubmitCommand cmd = new OrderSubmitCommand(
                1L, "BTC/USDT", MarketType.SPOT, OrderSide.BUY, OrderType.LIMIT,
                new BigDecimal("0.1"), new BigDecimal("42000"), null, TimeInForce.GTC, null, "c1");

        OrderSubmitResult result = service.submit(cmd);
        assertThat(result.status()).isEqualTo(OrderStatus.FILLED);
        verify(executor, never()).submit(any());
    }

    @Test
    void submit_whenExecutorFails_throwsAndIncrementsRejected() {
        doThrow(new RuntimeException("executor crashed")).when(executor).submit(any(Order.class));

        OrderSubmitCommand cmd = new OrderSubmitCommand(
                1L, "BTC/USDT", MarketType.SPOT, OrderSide.BUY, OrderType.LIMIT,
                new BigDecimal("0.1"), new BigDecimal("42000"), null, TimeInForce.GTC, null, "c1");

        assertThatThrownBy(() -> service.submit(cmd))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("executor crashed");
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

    @Test
    void getOrder_whenNotOwner_throwsOrderNotFound() {
        Order order = new Order();
        order.setId(50L);
        order.setAccountId(1L);
        when(orderMapper.findById(50L)).thenReturn(order);
        when(accountService.getOwned(1L, 42L)).thenThrow(new RuntimeException("not yours"));

        // loadOwnedAccountSilent converts AccessDeniedException to OrderNotFoundException (anti-probing)
        assertThatThrownBy(() -> service.getOrder(50L))
                .isInstanceOf(OrderNotFoundException.class);
    }

    @Test
    void cancel_whenCasUpdateFails_returnsLatestState() {
        Order order = new Order();
        order.setId(60L);
        order.setAccountId(1L);
        order.setStatus(OrderStatus.SUBMITTED);
        order.setVersion(2L);
        when(orderMapper.findById(60L)).thenReturn(order);
        when(orderMapper.casUpdate(any())).thenReturn(0); // CAS conflict
        Order latest = new Order();
        latest.setId(60L);
        latest.setAccountId(1L);
        latest.setStatus(OrderStatus.FILLED);
        latest.setVersion(3L);
        when(orderMapper.findById(60L)).thenReturn(order).thenReturn(latest);

        OrderCancelResult result = service.cancel(60L);
        assertThat(result.status()).isEqualTo(OrderStatus.FILLED);
        verify(executor, never()).cancel(any());
    }

    @Test
    void cancel_whenExecutorCancelFails_doesNotThrow() {
        Order order = new Order();
        order.setId(60L);
        order.setAccountId(1L);
        order.setStatus(OrderStatus.SUBMITTED);
        order.setVersion(2L);
        when(orderMapper.findById(60L)).thenReturn(order);
        when(orderMapper.casUpdate(any())).thenReturn(1);
        doThrow(new RuntimeException("cancel failed")).when(executor).cancel(any());

        // executor.cancel failure is caught and logged — should not propagate
        assertThatCode(() -> service.cancel(60L)).doesNotThrowAnyException();
    }

    @Test
    void submit_marketOrder_whenTickerNull_notionalIsNull() {
        // MARKET order with price=null and no ticker available → notional=null
        // RiskCheckRequest.notionalValue should be null; evaluator handles it
        when(marketDataService.getLatestTicker(any(), any(), any())).thenReturn(null);

        OrderSubmitCommand cmd = new OrderSubmitCommand(
                1L, "BTC/USDT", MarketType.SPOT, OrderSide.BUY, OrderType.MARKET,
                new BigDecimal("0.1"), null, null, TimeInForce.GTC, null, "c1");

        OrderSubmitResult result = service.submit(cmd);
        assertThat(result.orderId()).isEqualTo(999L);
        // Verify risk check was called (with null notional)
        verify(riskService).check(any(RiskCheckRequest.class));
    }
}
