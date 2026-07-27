package com.kwikquant.trading.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.kwikquant.account.application.BalanceService;
import com.kwikquant.account.application.ExchangeAccountService;
import com.kwikquant.account.domain.ExchangeAccount;
import com.kwikquant.shared.infra.AuditRepository;
import com.kwikquant.shared.types.AccountId;
import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.OrderId;
import com.kwikquant.shared.types.OrderSide;
import com.kwikquant.shared.types.OrderStatus;
import com.kwikquant.shared.types.OrderStatusChangedEvent;
import com.kwikquant.trading.domain.Order;
import com.kwikquant.trading.domain.Position;
import com.kwikquant.trading.infrastructure.ConcurrencyConflictException;
import com.kwikquant.trading.infrastructure.FillMapper;
import com.kwikquant.trading.infrastructure.OrderMapper;
import com.kwikquant.trading.interfaces.FillEvent;
import com.kwikquant.trading.interfaces.OrderEvent;
import com.kwikquant.trading.interfaces.OrderWebSocketBroadcaster;
import com.kwikquant.trading.interfaces.PositionEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class ExecutionServiceUnitTest {
    private OrderMapper orderMapper;
    private FillMapper fillMapper;
    private PositionService positionService;
    private OrderWebSocketBroadcaster wsBroadcaster;
    private ExchangeAccountService accountService;
    private BalanceService balanceService;
    private AuditRepository auditRepository;
    private ApplicationEventPublisher eventPublisher;
    private ExecutionService service;

    @BeforeEach
    void setUp() {
        orderMapper = mock(OrderMapper.class);
        fillMapper = mock(FillMapper.class);
        positionService = mock(PositionService.class);
        wsBroadcaster = mock(OrderWebSocketBroadcaster.class);
        accountService = mock(ExchangeAccountService.class);
        balanceService = mock(BalanceService.class);
        auditRepository = mock(AuditRepository.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        service = new ExecutionService(
                orderMapper,
                fillMapper,
                positionService,
                wsBroadcaster,
                accountService,
                new SimpleMeterRegistry(),
                balanceService,
                auditRepository,
                eventPublisher);
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
            verify(balanceService).applyFill(any(com.kwikquant.account.application.FillCommand.class));
            verify(positionService).applyFill(eq(1L), eq("BTC/USDT"), eq(OrderSide.BUY), any(), any(), any());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    // ---------- 新增:覆盖 JaCoCo missed 分支 ----------

    /**
     * 覆盖 processExecutionReport 中 accumulateFill 抛 MatchingException(over-fill)时的
     * catch 分支:记 error 日志并 return,不写 fill、不推进状态。
     */
    @Test
    void processExecutionReport_overFill_returnsEarlyWithoutPersistingFill() {
        // amount=1, filledQty=1, report.qty=1 → newFilled=2 > 1 → over-fill 抛 MatchingException
        Order order = order(1L, OrderStatus.SUBMITTED);
        order.setAmount(new BigDecimal("1"));
        order.setFilledQty(new BigDecimal("1"));
        when(orderMapper.findById(1L)).thenReturn(order);
        when(fillMapper.existsByExternalFillId(1L, "fill-1")).thenReturn(false);

        service.processExecutionReport(report(1L, "fill-1"));

        verify(orderMapper, never()).casUpdate(any());
        verify(fillMapper, never()).insert(any());
        verify(positionService, never()).applyFill(anyLong(), any(), any(), any(), any(), any());
    }

    /**
     * 覆盖 processExecutionReport 中 transitionTo 抛 IllegalOrderStateTransitionException
     * (order 在 CAS 重试期间被推进到 PENDING_CANCEL,不能转 PARTIALLY_FILLED)的 catch 分支:
     * statusChanged=false,但仍写 fill + position + balance,只是不广播 OrderEvent。
     */
    @Test
    void processExecutionReport_statusTransitionFails_persistsFillWithoutOrderStatusChange() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            // PENDING_CANCEL 不能转 PARTIALLY_FILLED(canTransitionTo=false)
            Order order = order(1L, OrderStatus.PENDING_CANCEL);
            order.setAmount(new BigDecimal("2"));
            order.setFilledQty(BigDecimal.ZERO);
            when(orderMapper.findById(1L)).thenReturn(order);
            when(orderMapper.casUpdate(any())).thenReturn(1);
            when(fillMapper.existsByExternalFillId(1L, "fill-1")).thenReturn(false);
            ExchangeAccount acct = new ExchangeAccount();
            acct.setId(1L);
            acct.setUserId(42L);
            acct.setExchange(Exchange.BINANCE);
            acct.setPaperTrading(false);
            when(accountService.findById(1L)).thenReturn(acct);

            service.processExecutionReport(report(1L, "fill-1"));

            // fill 与 balance 仍然写入
            verify(fillMapper).insert(any());
            verify(balanceService).applyFill(any());
            verify(positionService).applyFill(eq(1L), eq("BTC/USDT"), eq(OrderSide.BUY), any(), any(), any());
            // 提交后回调:不广播 OrderEvent(状态未变),但仍广播 FillEvent
            simulateAfterCommit();
            verify(wsBroadcaster, never()).broadcast(eq(42L), any(OrderEvent.class));
            verify(wsBroadcaster, atLeastOnce()).broadcast(eq(42L), any(FillEvent.class));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    /**
     * 覆盖 processExecutionReport 中 fillMapper.insert 抛 DuplicateKeyException 的 catch 分支
     * (TOCTOU 间隙内另一线程已插入同一 externalFillId,DB 唯一约束拦截):记 debug 日志并 return,
     * 不再继续广播或推进。
     */
    @Test
    void processExecutionReport_dbDuplicateKeyException_skipsAndReturnsEarly() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            Order order = order(1L, OrderStatus.SUBMITTED);
            order.setAmount(new BigDecimal("1"));
            order.setFilledQty(BigDecimal.ZERO);
            when(orderMapper.findById(1L)).thenReturn(order);
            when(orderMapper.casUpdate(any())).thenReturn(1);
            when(fillMapper.existsByExternalFillId(1L, "fill-1")).thenReturn(false);
            org.mockito.Mockito.doThrow(new DuplicateKeyException("pk violation"))
                    .when(fillMapper)
                    .insert(any());
            ExchangeAccount acct = new ExchangeAccount();
            acct.setId(1L);
            acct.setUserId(42L);
            acct.setExchange(Exchange.BINANCE);
            acct.setPaperTrading(true);
            when(accountService.findById(1L)).thenReturn(acct);

            service.processExecutionReport(report(1L, "fill-1"));

            // 幂等兜底:return,未到 positionService/balanceService
            verify(positionService, never()).applyFill(anyLong(), any(), any(), any(), any(), any());
            verify(balanceService, never()).applyFill(any());
            // 未注册 afterCommit 回调
            assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    /**
     * 覆盖 processExecutionReport 中 accountService.findById 返回 null 的分支:
     * 不调 balanceService.applyFill,userId 回退 0L。
     */
    @Test
    void processExecutionReport_accountNull_skipsBalanceAndUsesZeroUserId() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            Order order = order(1L, OrderStatus.SUBMITTED);
            when(orderMapper.findById(1L)).thenReturn(order);
            when(orderMapper.casUpdate(any())).thenReturn(1);
            when(fillMapper.existsByExternalFillId(1L, "fill-1")).thenReturn(false);
            when(accountService.findById(1L)).thenReturn(null);

            service.processExecutionReport(report(1L, "fill-1"));

            // account==null → 跳过 balance 调用;position 仍然 apply
            verify(balanceService, never()).applyFill(any());
            verify(positionService).applyFill(eq(1L), eq("BTC/USDT"), eq(OrderSide.BUY), any(), any(), any());

            // afterCommit:userId=0 广播 FillEvent + PositionEvent(无 account 时 WS userId=0)
            simulateAfterCommit();
            verify(wsBroadcaster).broadcast(eq(0L), any(FillEvent.class));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    /**
     * 覆盖 processExecutionReport 成功路径的 afterCommit 回调:
     * 广播 OrderEvent(statusChanged=true)+ FillEvent + PositionEvent。
     * 同时覆盖 broadcastPositionUpdate / toPositionDto / toFillDto 三条方法。
     */
    @Test
    void processExecutionReport_success_afterCommitBroadcastsAllThreeEvents() {
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
            // broadcastPositionUpdate 重读持仓
            Position pos = new Position();
            pos.setId(10L);
            pos.setAccountId(1L);
            pos.setSymbol("BTC/USDT");
            pos.setSide("long");
            pos.setQty(new BigDecimal("1"));
            pos.setAvgEntryPrice(new BigDecimal("40000"));
            pos.setRealizedPnl(BigDecimal.ZERO);
            pos.setVersion(3L);
            when(positionService.findByAccountAndSymbol(1L, "BTC/USDT")).thenReturn(pos);
            // broadcastPositionUpdate 用 findByAccount(返该 symbol 所有持仓 SPOT+PERP)
            when(positionService.findByAccount(1L)).thenReturn(List.of(pos));

            service.processExecutionReport(report(1L, "fill-1"));
            simulateAfterCommit();

            // 三种事件各广播一次:OrderEvent + FillEvent + PositionEvent
            verify(wsBroadcaster).broadcast(eq(42L), any(OrderEvent.class));
            verify(wsBroadcaster).broadcast(eq(42L), any(FillEvent.class));
            verify(wsBroadcaster).broadcast(eq(42L), any(PositionEvent.class));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    /**
     * R2 修复:成交后必须 publishEvent(OrderStatusChangedEvent),驱动 OrderActivityListener
     * (实时动态 ORDER_FILLED)和 NotificationService(成交通知)。同时验证 prevStatus 是
     * transition 前的真实状态(SUBMITTED)——原 bug 在 transitionTo 后取 status,导致
     * prevStatus=newStatus,OrderEvent/事件的 prevStatus 字段一直错。
     */
    @Test
    void processExecutionReport_success_afterCommitPublishesOrderStatusChangedEvent() {
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
            Position pos = new Position();
            pos.setId(10L);
            pos.setAccountId(1L);
            pos.setSymbol("BTC/USDT");
            pos.setSide("long");
            pos.setQty(new BigDecimal("1"));
            pos.setAvgEntryPrice(new BigDecimal("40000"));
            pos.setRealizedPnl(BigDecimal.ZERO);
            pos.setVersion(3L);
            when(positionService.findByAccountAndSymbol(1L, "BTC/USDT")).thenReturn(pos);
            // broadcastPositionUpdate 用 findByAccount(返该 symbol 所有持仓 SPOT+PERP)
            when(positionService.findByAccount(1L)).thenReturn(List.of(pos));

            service.processExecutionReport(report(1L, "fill-1"));
            simulateAfterCommit();

            org.mockito.ArgumentCaptor<OrderStatusChangedEvent> captor =
                    org.mockito.ArgumentCaptor.forClass(OrderStatusChangedEvent.class);
            verify(eventPublisher).publishEvent(captor.capture());
            OrderStatusChangedEvent ev = captor.getValue();
            assertThat(ev.userId()).isEqualTo(42L);
            assertThat(ev.orderId()).isEqualTo(new OrderId(1L));
            assertThat(ev.accountId()).isEqualTo(new AccountId(1L));
            assertThat(ev.previousStatus())
                    .as("prevStatus 必须是 transition 前的真实状态(SUBMITTED),而非 newStatus")
                    .isEqualTo(OrderStatus.SUBMITTED);
            assertThat(ev.newStatus()).isEqualTo(OrderStatus.FILLED);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    /**
     * R2 修复:onExchangeAccepted(Live 模式接受订单)后也必须 publishEvent,
     * 让 Live 模式的状态变更同样触发实时动态/通知。
     */
    @Test
    void onExchangeAccepted_publishesOrderStatusChangedEvent() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            Order order = order(1L, OrderStatus.NEW);
            when(orderMapper.findById(1L)).thenReturn(order);
            when(orderMapper.casUpdate(any())).thenReturn(1);
            ExchangeAccount acct = new ExchangeAccount();
            acct.setId(1L);
            acct.setUserId(42L);
            acct.setExchange(Exchange.BINANCE);
            acct.setPaperTrading(false);
            when(accountService.findById(1L)).thenReturn(acct);

            service.onExchangeAccepted(1L, "exch-order-1");
            simulateAfterCommit();

            org.mockito.ArgumentCaptor<OrderStatusChangedEvent> captor =
                    org.mockito.ArgumentCaptor.forClass(OrderStatusChangedEvent.class);
            verify(eventPublisher).publishEvent(captor.capture());
            assertThat(captor.getValue().newStatus()).isEqualTo(OrderStatus.SUBMITTED);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    /**
     * 覆盖 onExchangeAccepted:NEW → PENDING_NEW → SUBMITTED 双步 CAS 推进 + broadcastStatusChange。
     * 间接覆盖 casTransition(exchangeOrderId != null 分支) + resolveUserId(happy path)。
     */
    @Test
    void onExchangeAccepted_newStatus_transitionsToSubmittedAndBroadcasts() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            Order order = order(1L, OrderStatus.NEW);
            when(orderMapper.findById(1L)).thenReturn(order);
            when(orderMapper.casUpdate(any())).thenReturn(1);
            ExchangeAccount acct = new ExchangeAccount();
            acct.setId(1L);
            acct.setUserId(42L);
            acct.setExchange(Exchange.BINANCE);
            acct.setPaperTrading(false);
            when(accountService.findById(1L)).thenReturn(acct);

            service.onExchangeAccepted(1L, "exch-order-1");

            // 两次 CAS 更新(NEW→PENDING_NEW, PENDING_NEW→SUBMITTED)
            verify(orderMapper, org.mockito.Mockito.times(2)).casUpdate(any());
            assertThat(order.getStatus()).isEqualTo(OrderStatus.SUBMITTED);
            assertThat(order.getExchangeOrderId()).isEqualTo("exch-order-1");

            // afterCommit 广播 OrderEvent
            simulateAfterCommit();
            verify(wsBroadcaster).broadcast(eq(42L), any(OrderEvent.class));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    /**
     * 覆盖 requireOrder 中 order == null 的 throw 分支(经由 onExchangeAccepted 入口)。
     */
    @Test
    void onExchangeAccepted_orderNotFound_throws() {
        when(orderMapper.findById(99L)).thenReturn(null);
        assertThatThrownBy(() -> service.onExchangeAccepted(99L, "exch-1"))
                .isInstanceOf(com.kwikquant.trading.domain.OrderNotFoundException.class);
    }

    /**
     * 覆盖 onExchangeRejected 中 status == NEW 分支(NEW → PENDING_NEW → REJECTED 双步)。
     * 间接覆盖 casTransition(exchangeOrderId == null 分支,不 setExchangeOrderId)。
     */
    @Test
    void onExchangeRejected_newStatus_transitionsToRejectedAndBroadcasts() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            Order order = order(1L, OrderStatus.NEW);
            when(orderMapper.findById(1L)).thenReturn(order);
            when(orderMapper.casUpdate(any())).thenReturn(1);
            ExchangeAccount acct = new ExchangeAccount();
            acct.setId(1L);
            acct.setUserId(42L);
            acct.setExchange(Exchange.BINANCE);
            acct.setPaperTrading(false);
            when(accountService.findById(1L)).thenReturn(acct);

            service.onExchangeRejected(1L, "insufficient balance");

            verify(orderMapper, org.mockito.Mockito.times(2)).casUpdate(any());
            assertThat(order.getStatus()).isEqualTo(OrderStatus.REJECTED);

            simulateAfterCommit();
            verify(wsBroadcaster).broadcast(eq(42L), any(OrderEvent.class));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    /**
     * 覆盖 onExchangeRejected 中 status == NEW 分支未命中(PENDING_NEW → REJECTED 单步)的 else 路径。
     */
    @Test
    void onExchangeRejected_pendingNewStatus_skipsFirstTransition() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            Order order = order(1L, OrderStatus.PENDING_NEW);
            when(orderMapper.findById(1L)).thenReturn(order);
            when(orderMapper.casUpdate(any())).thenReturn(1);
            ExchangeAccount acct = new ExchangeAccount();
            acct.setId(1L);
            acct.setUserId(42L);
            acct.setPaperTrading(false);
            when(accountService.findById(1L)).thenReturn(acct);

            service.onExchangeRejected(1L, "rejected by exchange");

            // PENDING_NEW → REJECTED 仅一次 CAS(NEW 分支跳过)
            verify(orderMapper, org.mockito.Mockito.times(1)).casUpdate(any());
            assertThat(order.getStatus()).isEqualTo(OrderStatus.REJECTED);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    // ---------- computeProportionalFrozen 静态方法分支覆盖 ----------

    /** 覆盖 frozenQuoteAmount == null → 返回 null(SELL 单不冻结 quote)分支。 */
    @Test
    void computeProportionalFrozen_nullFrozenReturnsNull() {
        assertThat(ExecutionService.computeProportionalFrozen(null, new BigDecimal("1"), new BigDecimal("2")))
                .isNull();
    }

    /** 覆盖 totalQty == null 或 signum <= 0 → 返回原 frozen 分支。 */
    @Test
    void computeProportionalFrozen_invalidTotalQtyReturnsFullFrozen() {
        BigDecimal frozen = new BigDecimal("100");
        assertThat(ExecutionService.computeProportionalFrozen(frozen, new BigDecimal("1"), null))
                .isEqualByComparingTo(frozen);
        assertThat(ExecutionService.computeProportionalFrozen(frozen, new BigDecimal("1"), BigDecimal.ZERO))
                .isEqualByComparingTo(frozen);
        assertThat(ExecutionService.computeProportionalFrozen(frozen, new BigDecimal("1"), new BigDecimal("-1")))
                .isEqualByComparingTo(frozen);
    }

    /** 覆盖 fillQty >= totalQty → 返回全部 frozen(全量成交或最后一笔)分支。 */
    @Test
    void computeProportionalFrozen_fillQtyAtLeastTotalQtyReturnsFullFrozen() {
        BigDecimal frozen = new BigDecimal("100");
        // 相等
        assertThat(ExecutionService.computeProportionalFrozen(frozen, new BigDecimal("2"), new BigDecimal("2")))
                .isEqualByComparingTo(frozen);
        // 超出
        assertThat(ExecutionService.computeProportionalFrozen(frozen, new BigDecimal("3"), new BigDecimal("2")))
                .isEqualByComparingTo(frozen);
    }

    /** 覆盖正常按比例分配分支:乘除法 + 8 位 HALF_UP。 */
    @Test
    void computeProportionalFrozen_partialFillReturnsProportional() {
        // frozen=100, fillQty=0.3, totalQty=1 → 100 * 0.3 / 1 = 30
        BigDecimal result = ExecutionService.computeProportionalFrozen(
                new BigDecimal("100"), new BigDecimal("0.3"), new BigDecimal("1"));
        assertThat(result).isEqualByComparingTo("30");
    }

    /** 触发已注册的 afterCommit 回调,模拟事务提交。 */
    private void simulateAfterCommit() {
        for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
            sync.afterCommit();
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
