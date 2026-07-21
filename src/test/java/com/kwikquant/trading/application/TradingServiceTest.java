package com.kwikquant.trading.application;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.kwikquant.account.application.BalanceService;
import com.kwikquant.account.application.ExchangeAccountService;
import com.kwikquant.account.domain.ExchangeAccount;
import com.kwikquant.account.domain.InsufficientBalanceException;
import com.kwikquant.market.application.MarketDataService;
import com.kwikquant.market.application.TradingPairService;
import com.kwikquant.market.domain.Ticker;
import com.kwikquant.market.domain.TradingPairInfo;
import com.kwikquant.risk.application.RiskService;
import com.kwikquant.risk.domain.RiskCheckRequest;
import com.kwikquant.risk.domain.RiskDecision;
import com.kwikquant.risk.domain.RiskRejectedException;
import com.kwikquant.risk.domain.RiskRuleType;
import com.kwikquant.risk.domain.RiskVerdict;
import com.kwikquant.risk.domain.RuleResult;
import com.kwikquant.shared.infra.AuditEntry;
import com.kwikquant.shared.infra.AuditRepository;
import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.MarginMode;
import com.kwikquant.shared.types.MarketType;
import com.kwikquant.shared.types.OrderSide;
import com.kwikquant.shared.types.OrderStatus;
import com.kwikquant.shared.types.OrderType;
import com.kwikquant.shared.types.PositionEffect;
import com.kwikquant.shared.types.RiskTriggeredEvent;
import com.kwikquant.trading.domain.InvalidOrderException;
import com.kwikquant.trading.domain.Order;
import com.kwikquant.trading.domain.OrderNotFoundException;
import com.kwikquant.trading.domain.OrderSubmitCommand;
import com.kwikquant.trading.domain.Position;
import com.kwikquant.trading.domain.TimeInForce;
import com.kwikquant.trading.infrastructure.FillMapper;
import com.kwikquant.trading.infrastructure.OrderMapper;
import com.kwikquant.trading.infrastructure.PositionMapper;
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
    private BalanceService balanceService;
    private TradingPairService pairService;
    private OrderMapper orderMapper;
    private FillMapper fillMapper;
    private PositionMapper positionMapper;
    private OrderRouter router;
    private Executor executor;
    private RiskService riskService;
    private MarketDataService marketDataService;
    private AuditRepository auditRepository;
    private ApplicationEventPublisher publisher;
    private TradingService service;
    private TradingTransactionHelper txHelper;
    private OrderMetricsService orderMetrics;

    @BeforeEach
    void setUp() {
        accountService = mock(ExchangeAccountService.class);
        balanceService = mock(BalanceService.class);
        pairService = mock(TradingPairService.class);
        orderMapper = mock(OrderMapper.class);
        fillMapper = mock(FillMapper.class);
        positionMapper = mock(PositionMapper.class);
        router = mock(OrderRouter.class);
        executor = mock(Executor.class);
        riskService = mock(RiskService.class);
        marketDataService = mock(MarketDataService.class);
        auditRepository = mock(AuditRepository.class);
        publisher = mock(ApplicationEventPublisher.class);
        txHelper = mock(TradingTransactionHelper.class);
        orderMetrics = new OrderMetricsService(orderMapper, fillMapper, marketDataService);
        service = new TradingService(
                accountService,
                pairService,
                orderMapper,
                fillMapper,
                positionMapper,
                router,
                riskService,
                auditRepository,
                publisher,
                new SimpleMeterRegistry(),
                balanceService,
                txHelper,
                orderMetrics);

        // 模拟登录用户
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("42", "x"));

        // account 1：真实交易所账户(paperTrading=false)，大多数生成通用测试用它。
        ExchangeAccount acct = new ExchangeAccount();
        acct.setId(1L);
        acct.setUserId(42L);
        acct.setExchange(Exchange.BINANCE);
        acct.setPaperTrading(false);
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
        // PERP pairInfo(合约测试用,阶段2d)
        when(pairService.getPairs(Exchange.BINANCE, MarketType.PERP))
                .thenReturn(List.of(new TradingPairInfo(
                        Exchange.BINANCE,
                        MarketType.PERP,
                        "BTC/USDT",
                        "BTC",
                        "USDT",
                        new BigDecimal("0.001"),
                        new BigDecimal("100"),
                        new BigDecimal("0.1"),
                        new BigDecimal("0.001"),
                        true)));

        when(router.route(any(ExchangeAccount.class))).thenReturn(executor);
        when(fillMapper.sumNetCashflow(anyLong(), any())).thenReturn(BigDecimal.ZERO);
        // Spring 代理会调 insertOrder（内部事务），test 不走 Spring AOP，直接 mock txHelper.insertOrder
        doAnswer(invocation -> {
                    Order o = invocation.getArgument(0);
                    o.setId(999L);
                    return null;
                })
                .when(txHelper)
                .insertOrder(any(Order.class));
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
        OrderSubmitCommand cmd = OrderSubmitCommand.spot(
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
        verify(txHelper).insertOrder(any(Order.class));
        verify(riskService).check(any(RiskCheckRequest.class));
        verify(executor).submit(any(Order.class));
    }

    @Test
    void submit_setsOrderExchangeFromAccount() {
        OrderSubmitCommand cmd = OrderSubmitCommand.spot(
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
                "c-exchange");

        service.submit(cmd);

        ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
        verify(executor).submit(captor.capture());
        assertThat(captor.getValue().getExchange()).isEqualTo(Exchange.BINANCE);
        verify(pairService).getPairs(Exchange.BINANCE, MarketType.SPOT);
    }

    // ---------- 余额冻结/解冻(模拟盘) ----------

    /** 模拟盘账户 helper：exchange=BINANCE(参考行情源)，paperTrading=true。 */
    private ExchangeAccount paperAccount(long id) {
        ExchangeAccount acct = new ExchangeAccount();
        acct.setId(id);
        acct.setUserId(42L);
        acct.setExchange(Exchange.BINANCE);
        acct.setPaperTrading(true);
        return acct;
    }

    @Test
    void submit_paperBuy_freezesQuoteCost() {
        when(accountService.getOwned(2L, 42L)).thenReturn(paperAccount(2L));

        OrderSubmitCommand cmd = OrderSubmitCommand.spot(
                2L,
                "BTC/USDT",
                MarketType.SPOT,
                OrderSide.BUY,
                OrderType.LIMIT,
                new BigDecimal("0.1"),
                new BigDecimal("42000"),
                null,
                TimeInForce.GTC,
                null,
                "c-buy");

        service.submit(cmd);

        verify(txHelper).freezeBalance(any(Order.class), argThat(a -> a.isPaperTrading()), any());
    }

    @Test
    void submit_paperSell_freezesBaseQty() {
        when(accountService.getOwned(2L, 42L)).thenReturn(paperAccount(2L));

        OrderSubmitCommand cmd = OrderSubmitCommand.spot(
                2L,
                "BTC/USDT",
                MarketType.SPOT,
                OrderSide.SELL,
                OrderType.LIMIT,
                new BigDecimal("0.1"),
                new BigDecimal("42000"),
                null,
                TimeInForce.GTC,
                null,
                "c-sell");

        service.submit(cmd);

        verify(txHelper).freezeBalance(any(Order.class), argThat(a -> a.isPaperTrading()), any());
    }

    @Test
    void submit_liveAccount_doesNotFreeze() {
        // account 1 是 setUp 里的真实交易所账户(paperTrading=false)
        OrderSubmitCommand cmd = OrderSubmitCommand.spot(
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
                "c-live");

        service.submit(cmd);

        verify(txHelper).freezeBalance(any(Order.class), argThat(a -> !a.isPaperTrading()), any());
    }

    @Test
    void submit_paperInsufficientBalance_rejectsOrderAndRethrows() {
        when(accountService.getOwned(2L, 42L)).thenReturn(paperAccount(2L));
        doThrow(new InsufficientBalanceException("free=100 required=4200"))
                .when(txHelper)
                .freezeBalance(any(Order.class), any(ExchangeAccount.class), any());

        OrderSubmitCommand cmd = OrderSubmitCommand.spot(
                2L,
                "BTC/USDT",
                MarketType.SPOT,
                OrderSide.BUY,
                OrderType.LIMIT,
                new BigDecimal("0.1"),
                new BigDecimal("42000"),
                null,
                TimeInForce.GTC,
                null,
                "c-noop");

        assertThatThrownBy(() -> service.submit(cmd))
                .isInstanceOf(InsufficientBalanceException.class)
                .hasMessageContaining("free=100");

        // order 应 CAS NEW → REJECTED
        org.mockito.ArgumentCaptor<Order> orderCaptor = org.mockito.ArgumentCaptor.forClass(Order.class);
        verify(orderMapper).casUpdate(orderCaptor.capture());
        assertThat(orderCaptor.getValue().getStatus()).isEqualTo(OrderStatus.REJECTED);
        // executor 不应被调(余额不足拒单,未到 executor)
        verify(executor, never()).submit(any());
    }

    @Test
    void submit_executorFails_compensatoryUnfreeze() {
        when(accountService.getOwned(2L, 42L)).thenReturn(paperAccount(2L));
        doThrow(new RuntimeException("executor crashed")).when(executor).submit(any());

        OrderSubmitCommand cmd = OrderSubmitCommand.spot(
                2L,
                "BTC/USDT",
                MarketType.SPOT,
                OrderSide.BUY,
                OrderType.LIMIT,
                new BigDecimal("0.1"),
                new BigDecimal("42000"),
                null,
                TimeInForce.GTC,
                null,
                "c-fail");

        assertThatThrownBy(() -> service.submit(cmd))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("executor crashed");

        // 补偿解冻:executor 失败,txHelper.unfreezeBalance 被调用
        verify(txHelper).unfreezeBalance(any(Order.class), any(ExchangeAccount.class));
    }

    @Test
    void cancel_paperOrder_unfreezesRemaining() {
        when(accountService.getOwned(2L, 42L)).thenReturn(paperAccount(2L));
        Order order = new Order();
        order.setId(60L);
        order.setAccountId(2L);
        order.setSymbol("BTC/USDT");
        order.setSide(OrderSide.BUY);
        order.setOrderType(OrderType.LIMIT);
        order.setPrice(new BigDecimal("42000"));
        order.setAmount(new BigDecimal("0.1"));
        order.setFilledQty(BigDecimal.ZERO); // 未成交,remaining = 0.1
        order.setStatus(OrderStatus.SUBMITTED);
        order.setVersion(2L);
        order.setExchange(Exchange.BINANCE);
        when(orderMapper.findById(60L)).thenReturn(order);
        when(orderMapper.casUpdate(any())).thenReturn(1);

        service.cancel(60L);

        verify(txHelper).unfreezeBalance(any(Order.class), any(ExchangeAccount.class));
    }

    /**
     * 顺序必须是 executor.cancel 先摘内存池，再 unfreeze——反过来会有一段窗口期
     * activeOrders 里还留着旧引用（status 仍是 SUBMITTED），此时来一个 ticker 会把已经
     * 解冻过的订单撮合成交，导致 applyFill 再解冻一次，把 used 冻出负数。
     */
    @Test
    void cancel_paperOrder_cancelsExecutorBeforeUnfreezing() {
        when(accountService.getOwned(2L, 42L)).thenReturn(paperAccount(2L));
        Order order = new Order();
        order.setId(60L);
        order.setAccountId(2L);
        order.setSymbol("BTC/USDT");
        order.setSide(OrderSide.BUY);
        order.setOrderType(OrderType.LIMIT);
        order.setPrice(new BigDecimal("42000"));
        order.setAmount(new BigDecimal("0.1"));
        order.setFilledQty(BigDecimal.ZERO);
        order.setStatus(OrderStatus.SUBMITTED);
        order.setVersion(2L);
        order.setExchange(Exchange.BINANCE);
        when(orderMapper.findById(60L)).thenReturn(order);
        when(orderMapper.casUpdate(any())).thenReturn(1);

        service.cancel(60L);

        org.mockito.InOrder inOrder = inOrder(executor, txHelper);
        inOrder.verify(executor).cancel(any(Order.class));
        inOrder.verify(txHelper).unfreezeBalance(any(Order.class), any(ExchangeAccount.class));
    }

    @Test
    void cancel_liveOrder_doesNotUnfreeze() {
        Order order = new Order();
        order.setId(60L);
        order.setAccountId(1L);
        order.setSymbol("BTC/USDT");
        order.setSide(OrderSide.BUY);
        order.setOrderType(OrderType.LIMIT);
        order.setPrice(new BigDecimal("42000"));
        order.setAmount(new BigDecimal("0.1"));
        order.setFilledQty(BigDecimal.ZERO);
        order.setStatus(OrderStatus.SUBMITTED);
        order.setVersion(2L);
        order.setExchange(Exchange.BINANCE);
        when(orderMapper.findById(60L)).thenReturn(order);
        when(orderMapper.casUpdate(any())).thenReturn(1);

        service.cancel(60L);

        verify(txHelper).unfreezeBalance(any(Order.class), argThat(a -> !a.isPaperTrading()));
    }

    @Test
    void submitRejectsUnknownSymbol() {
        OrderSubmitCommand cmd = OrderSubmitCommand.spot(
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
        verify(txHelper, never()).insertOrder(any());
        verify(executor, never()).submit(any());
    }

    @Test
    void submitRejectsWhenRiskCheckRejects() {
        RiskDecision rejectedDecision = new RiskDecision();
        rejectedDecision.setVerdict(RiskVerdict.REJECTED);
        rejectedDecision.setRuleResults(
                List.of(new RuleResult(RiskRuleType.MAX_NOTIONAL, false, "notional exceeds limit")));
        when(riskService.check(any(RiskCheckRequest.class))).thenReturn(rejectedDecision);

        OrderSubmitCommand cmd = OrderSubmitCommand.spot(
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
        when(riskService.check(any(RiskCheckRequest.class))).thenThrow(new RuntimeException("risk service down"));

        Position longPos = new Position();
        longPos.setSide(Position.SIDE_LONG);
        longPos.setQty(new BigDecimal("1"));
        when(positionMapper.findByAccountAndSymbol(1L, "BTC/USDT")).thenReturn(longPos);

        OrderSubmitCommand cmd = OrderSubmitCommand.spot(
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
    void submitBypassesRiskForShortPositionBuyStopOnServiceFailure() {
        when(riskService.check(any(RiskCheckRequest.class))).thenThrow(new RuntimeException("risk service down"));

        Position shortPos = new Position();
        shortPos.setSide(Position.SIDE_SHORT);
        shortPos.setQty(new BigDecimal("1"));
        when(positionMapper.findByAccountAndSymbol(1L, "BTC/USDT")).thenReturn(shortPos);

        OrderSubmitCommand cmd = OrderSubmitCommand.spot(
                1L,
                "BTC/USDT",
                MarketType.SPOT,
                OrderSide.BUY,
                OrderType.STOP_MARKET,
                new BigDecimal("0.1"),
                null,
                new BigDecimal("45000"),
                TimeInForce.GTC,
                null,
                "c1");

        OrderSubmitResult result = service.submit(cmd);
        assertThat(result.orderId()).isEqualTo(999L);
        verify(executor).submit(any(Order.class));
        verify(auditRepository).save(any(AuditEntry.class));
    }

    @Test
    void submitRejectsNonPositionReducingOrderOnRiskServiceFailure() {
        when(riskService.check(any(RiskCheckRequest.class))).thenThrow(new RuntimeException("risk service down"));

        OrderSubmitCommand cmd = OrderSubmitCommand.spot(
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
        when(riskService.check(any(RiskCheckRequest.class))).thenThrow(new RuntimeException("risk service down"));
        doThrow(new RuntimeException("audit DB down")).when(auditRepository).save(any(AuditEntry.class));

        Position longPos = new Position();
        longPos.setSide(Position.SIDE_LONG);
        longPos.setQty(new BigDecimal("1"));
        when(positionMapper.findByAccountAndSymbol(1L, "BTC/USDT")).thenReturn(longPos);

        OrderSubmitCommand cmd = OrderSubmitCommand.spot(
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

        // Audit failure must NOT block the bypass — order still proceeds to executor
        OrderSubmitResult result = service.submit(cmd);
        assertThat(result.orderId()).isEqualTo(999L);
        verify(executor).submit(any(Order.class));
    }

    @Test
    void submitNonPositionReducing_whenCasUpdateFails_returnsLatestState() {
        when(riskService.check(any(RiskCheckRequest.class))).thenThrow(new RuntimeException("risk service down"));
        // casUpdate returns 0 → concurrent transition → re-read latest
        when(orderMapper.casUpdate(any(Order.class))).thenReturn(0);
        Order latest = new Order();
        latest.setId(999L);
        latest.setAccountId(1L);
        latest.setStatus(OrderStatus.SUBMITTED); // another thread already moved it
        latest.setVersion(2L);
        latest.setCreatedAt(Instant.now());
        when(orderMapper.findById(999L)).thenReturn(latest);

        OrderSubmitCommand cmd = OrderSubmitCommand.spot(
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

        OrderSubmitCommand cmd = OrderSubmitCommand.spot(
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
        assertThat(result.status()).isEqualTo(OrderStatus.FILLED);
        verify(executor, never()).submit(any());
    }

    @Test
    void submit_whenExecutorFails_throwsAndIncrementsRejected() {
        doThrow(new RuntimeException("executor crashed")).when(executor).submit(any(Order.class));

        OrderSubmitCommand cmd = OrderSubmitCommand.spot(
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
        order.setSymbol("BTC/USDT");
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
        assertThatThrownBy(() -> service.getOrder(50L)).isInstanceOf(OrderNotFoundException.class);
    }

    @Test
    void cancel_whenCasUpdateFails_returnsLatestState() {
        Order order = new Order();
        order.setId(60L);
        order.setAccountId(1L);
        order.setSymbol("BTC/USDT");
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
        order.setSymbol("BTC/USDT");
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
        // MARKET BUY with price=null and no ticker available → fail-fast reject
        // (null marketPrice would bypass risk check and cause freeze/risk inconsistency)
        when(marketDataService.getLatestTicker(any(), any(), any())).thenReturn(null);

        OrderSubmitCommand cmd = OrderSubmitCommand.spot(
                1L,
                "BTC/USDT",
                MarketType.SPOT,
                OrderSide.BUY,
                OrderType.MARKET,
                new BigDecimal("0.1"),
                null,
                null,
                TimeInForce.GTC,
                null,
                "c1");

        // Should be rejected due to missing ticker price
        assertThrows(com.kwikquant.trading.domain.InvalidOrderException.class, () -> service.submit(cmd));
    }

    @Test
    void submit_marketOrder_whenTickerAvailable_usesTickerPrice() {
        // MARKET order with price=null but ticker available → notional = amount * ticker.last()
        Ticker ticker = new Ticker(
                Exchange.BINANCE,
                MarketType.SPOT,
                "BTC/USDT",
                new BigDecimal("50000"),
                new BigDecimal("49999"),
                new BigDecimal("50001"),
                new BigDecimal("51000"),
                new BigDecimal("49000"),
                new BigDecimal("49500"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                Instant.now(),
                Instant.now());
        when(marketDataService.getLatestTicker(any(), any(), any())).thenReturn(ticker);

        OrderSubmitCommand cmd = OrderSubmitCommand.spot(
                1L,
                "BTC/USDT",
                MarketType.SPOT,
                OrderSide.BUY,
                OrderType.MARKET,
                new BigDecimal("0.1"),
                null,
                null,
                TimeInForce.GTC,
                null,
                "c1");

        OrderSubmitResult result = service.submit(cmd);
        assertThat(result.orderId()).isEqualTo(999L);
        // Verify risk check was called with notional = 0.1 * 50000 = 5000
        ArgumentCaptor<RiskCheckRequest> captor = ArgumentCaptor.forClass(RiskCheckRequest.class);
        verify(riskService).check(captor.capture());
        assertThat(captor.getValue().notionalValue()).isEqualByComparingTo("5000");
    }

    @Test
    void listOpenByAccount_delegatesToOrderMapperFindActive() {
        Order open = new Order();
        open.setId(1L);
        open.setAccountId(7L);
        open.setSymbol("BTC/USDT");
        open.setStatus(OrderStatus.SUBMITTED);
        when(orderMapper.findActiveByAccount(7L)).thenReturn(List.of(open));

        List<Order> result = service.listOpenByAccount(7L);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo(1L);
        verify(orderMapper).findActiveByAccount(7L);
    }

    // ── R3-03 薄查询：report 经此访问 trading 数据，转发 mapper（不绕 service 边界）──

    @Test
    void queryOrders_delegatesToOrderMapperFindByQuery() {
        Order o = new Order();
        o.setId(1L);
        o.setStatus(OrderStatus.FILLED);
        when(orderMapper.findByQuery(eq(7L), eq("BTC/USDT"), any(), any(), any(), eq(20), eq(0)))
                .thenReturn(List.of(o));

        List<Order> result = service.queryOrders(7L, "BTC/USDT", List.of(OrderStatus.FILLED), null, null, 20, 0);

        assertThat(result).hasSize(1);
        verify(orderMapper).findByQuery(7L, "BTC/USDT", List.of(OrderStatus.FILLED), null, null, 20, 0);
    }

    @Test
    void countOrders_delegatesToOrderMapperCountByQuery() {
        when(orderMapper.countByQuery(eq(7L), isNull(), any(), isNull(), isNull()))
                .thenReturn(42L);

        long result = service.countOrders(7L, null, List.of(OrderStatus.FILLED), null, null);

        assertThat(result).isEqualTo(42L);
        verify(orderMapper).countByQuery(7L, null, List.of(OrderStatus.FILLED), null, null);
    }

    @Test
    void listFillsByOrder_delegatesToFillMapperFindByOrderId() {
        com.kwikquant.trading.domain.Fill f = new com.kwikquant.trading.domain.Fill();
        f.setId(1L);
        when(fillMapper.findByOrderId(99L)).thenReturn(List.of(f));

        List<com.kwikquant.trading.domain.Fill> result = service.listFillsByOrder(99L);

        assertThat(result).hasSize(1);
        verify(fillMapper).findByOrderId(99L);
    }

    @Test
    void sumNetCashflow_delegatesToFillMapperSumNetCashflow() {
        when(fillMapper.sumNetCashflow(eq(7L), any())).thenReturn(new BigDecimal("123.45"));

        BigDecimal result = service.sumNetCashflow(7L, Instant.parse("2024-01-01T00:00:00Z"));

        assertThat(result).isEqualByComparingTo("123.45");
        verify(fillMapper).sumNetCashflow(eq(7L), eq(Instant.parse("2024-01-01T00:00:00Z")));
    }

    // ---------- 重置模拟盘账户 ----------

    @Test
    void resetPaperAccount_cancelsOrdersClearsPoolDeletesPositionsResetsBalance() {
        when(accountService.getOwned(2L, 42L)).thenReturn(paperAccount(2L));

        service.resetPaperAccount(2L, 42L);

        verify(orderMapper).cancelAllActiveByAccount(2L);
        verify(executor).clearActiveOrdersByAccount(2L);
        verify(positionMapper).deleteByAccount(2L);
        verify(balanceService).reset(2L, true);
    }

    @Test
    void resetPaperAccount_nonPaper_throwsAndNoSideEffects() {
        // account 1(paperTrading=false)在 setUp stub;非模拟盘账户重置应抛
        assertThatThrownBy(() -> service.resetPaperAccount(1L, 42L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("paper");
        verify(orderMapper, never()).cancelAllActiveByAccount(anyLong());
        verify(positionMapper, never()).deleteByAccount(anyLong());
        verify(balanceService, never()).reset(anyLong(), anyBoolean());
    }

    // ---------- 阶段2d:PERP CLOSE_* pre-trade gate(§12 B2-s ①) ----------

    /** PERP CLOSE_LONG amount > position.qty → 拒单(reduceOnly 防反手)。 */
    @Test
    void submitPerpCloseLong_overPosition_rejectsWithInvalidOrder() {
        when(accountService.getOwned(2L, 42L)).thenReturn(paperAccount(2L));
        Position longPos = new Position();
        longPos.setQty(new BigDecimal("0.1"));
        when(positionMapper.findByAccountSymbolPosition(2L, "BTC/USDT", "LONG", MarginMode.ISOLATED))
                .thenReturn(longPos);

        OrderSubmitCommand cmd = OrderSubmitCommand.perp(
                2L,
                "BTC/USDT",
                OrderSide.SELL,
                OrderType.LIMIT,
                new BigDecimal("0.2"), // > 0.1 持仓量
                new BigDecimal("42000"),
                null,
                TimeInForce.GTC,
                null,
                "c-close-over",
                10,
                MarginMode.ISOLATED,
                PositionEffect.CLOSE_LONG);

        assertThatThrownBy(() -> service.submit(cmd))
                .isInstanceOf(InvalidOrderException.class)
                .hasMessageContaining("PERP CLOSE over-position")
                .hasMessageContaining("amount=0.2")
                .hasMessageContaining("qty=0.1");

        // 订单 CAS NEW→REJECTED
        org.mockito.ArgumentCaptor<Order> captor = org.mockito.ArgumentCaptor.forClass(Order.class);
        verify(orderMapper).casUpdate(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(OrderStatus.REJECTED);
        // gate 在 freezeBalance 前,余额未冻,executor 未调
        verify(txHelper, never()).freezeBalance(any(Order.class), any(ExchangeAccount.class), any());
        verify(executor, never()).submit(any());
    }

    /** PERP CLOSE_LONG 无持仓(qty=0)→ 拒单(reduceOnly 不能凭空开反向仓)。 */
    @Test
    void submitPerpCloseLong_noPosition_rejectsWithInvalidOrder() {
        when(accountService.getOwned(2L, 42L)).thenReturn(paperAccount(2L));
        when(positionMapper.findByAccountSymbolPosition(2L, "BTC/USDT", "LONG", MarginMode.ISOLATED))
                .thenReturn(null); // 无持仓

        OrderSubmitCommand cmd = OrderSubmitCommand.perp(
                2L,
                "BTC/USDT",
                OrderSide.SELL,
                OrderType.LIMIT,
                new BigDecimal("0.1"),
                new BigDecimal("42000"),
                null,
                TimeInForce.GTC,
                null,
                "c-close-empty",
                10,
                MarginMode.ISOLATED,
                PositionEffect.CLOSE_LONG);

        assertThatThrownBy(() -> service.submit(cmd))
                .isInstanceOf(InvalidOrderException.class)
                .hasMessageContaining("PERP CLOSE over-position")
                .hasMessageContaining("qty=0");

        verify(orderMapper).casUpdate(any(Order.class));
        verify(txHelper, never()).freezeBalance(any(Order.class), any(ExchangeAccount.class), any());
        verify(executor, never()).submit(any());
    }

    /** PERP CLOSE_SHORT amount = position.qty(边界等量)→ 通过 gate(>= 是 over,= 不是)。 */
    @Test
    void submitPerpCloseShort_amountEqualsQty_passesGateAndFreezesNoMargin() {
        when(accountService.getOwned(2L, 42L)).thenReturn(paperAccount(2L));
        Position shortPos = new Position();
        shortPos.setQty(new BigDecimal("0.1"));
        when(positionMapper.findByAccountSymbolPosition(2L, "BTC/USDT", "SHORT", MarginMode.ISOLATED))
                .thenReturn(shortPos);

        OrderSubmitCommand cmd = OrderSubmitCommand.perp(
                2L,
                "BTC/USDT",
                OrderSide.BUY,
                OrderType.LIMIT,
                new BigDecimal("0.1"), // = 0.1 持仓量,等量平仓允许
                new BigDecimal("42000"),
                null,
                TimeInForce.GTC,
                null,
                "c-close-eq",
                10,
                MarginMode.ISOLATED,
                PositionEffect.CLOSE_SHORT);

        service.submit(cmd);

        verify(positionMapper).findByAccountSymbolPosition(2L, "BTC/USDT", "SHORT", MarginMode.ISOLATED);
        // CLOSE_* reduceOnly 不冻保证金,freezeBalance 调到但内部 noop
        verify(txHelper).freezeBalance(any(Order.class), any(ExchangeAccount.class), any());
        verify(executor).submit(any(Order.class));
    }

    /** PERP OPEN_LONG 不查 position(gate 仅 CLOSE_* 触发),走 freezeBalance 冻 initialMargin。 */
    @Test
    void submitPerpOpenLong_skipsGateAndFreezesInitialMargin() {
        when(accountService.getOwned(2L, 42L)).thenReturn(paperAccount(2L));

        OrderSubmitCommand cmd = OrderSubmitCommand.perp(
                2L,
                "BTC/USDT",
                OrderSide.BUY,
                OrderType.LIMIT,
                new BigDecimal("0.1"),
                new BigDecimal("42000"),
                null,
                TimeInForce.GTC,
                null,
                "c-open-long",
                10,
                MarginMode.ISOLATED,
                PositionEffect.OPEN_LONG);

        service.submit(cmd);

        // OPEN_* 不查 position(gate skip)
        verify(positionMapper, never()).findByAccountSymbolPosition(anyLong(), anyString(), anyString(), any());
        verify(txHelper).freezeBalance(any(Order.class), any(ExchangeAccount.class), any());
        verify(executor).submit(any(Order.class));
    }

    /**
     * PERP 平仓单 risk service 故障 → bypass(isPositionReducing 改判 §10 M9)。
     * positionEffect=CLOSE_* → order.isReduceOnly()=true → isPositionReducing 返 true。
     */
    @Test
    void submitPerpCloseOrder_bypassesRiskOnServiceFailure() {
        when(accountService.getOwned(2L, 42L)).thenReturn(paperAccount(2L));
        when(riskService.check(any(RiskCheckRequest.class))).thenThrow(new RuntimeException("risk service down"));
        Position longPos = new Position();
        longPos.setQty(new BigDecimal("0.5")); // 充足,过 gate
        when(positionMapper.findByAccountSymbolPosition(2L, "BTC/USDT", "LONG", MarginMode.ISOLATED))
                .thenReturn(longPos);

        OrderSubmitCommand cmd = OrderSubmitCommand.perp(
                2L,
                "BTC/USDT",
                OrderSide.SELL,
                OrderType.LIMIT,
                new BigDecimal("0.1"),
                new BigDecimal("42000"),
                null,
                TimeInForce.GTC,
                null,
                "c-close-risk-down",
                10,
                MarginMode.ISOLATED,
                PositionEffect.CLOSE_LONG);

        OrderSubmitResult result = service.submit(cmd);

        // bypass 走通,推进到 executor
        assertThat(result.orderId()).isEqualTo(999L);
        verify(executor).submit(any(Order.class));
        verify(auditRepository).save(any(AuditEntry.class));
    }

    /**
     * PERP OPEN_SHORT risk service 故障 → 不 bypass(OPEN_* isReduceOnly=false →
     * isPositionReducing=false → 拒单)。验证 §10 M9 改判不误 bypass 开仓单。
     */
    @Test
    void submitPerpOpenOrder_doesNotBypassOnRiskServiceFailure() {
        when(accountService.getOwned(2L, 42L)).thenReturn(paperAccount(2L));
        when(riskService.check(any(RiskCheckRequest.class))).thenThrow(new RuntimeException("risk service down"));

        OrderSubmitCommand cmd = OrderSubmitCommand.perp(
                2L,
                "BTC/USDT",
                OrderSide.SELL,
                OrderType.LIMIT,
                new BigDecimal("0.1"),
                new BigDecimal("42000"),
                null,
                TimeInForce.GTC,
                null,
                "c-open-risk-down",
                10,
                MarginMode.ISOLATED,
                PositionEffect.OPEN_SHORT);

        assertThatThrownBy(() -> service.submit(cmd))
                .isInstanceOf(RiskRejectedException.class)
                .hasMessageContaining("risk service unavailable");

        verify(executor, never()).submit(any());
        verify(txHelper, never()).freezeBalance(any(Order.class), any(ExchangeAccount.class), any());
    }
}
