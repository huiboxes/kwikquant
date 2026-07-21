package com.kwikquant.trading.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kwikquant.account.application.BalanceService;
import com.kwikquant.account.application.ExchangeAccountService;
import com.kwikquant.account.domain.ExchangeAccount;
import com.kwikquant.shared.infra.AuditEntry;
import com.kwikquant.shared.infra.AuditRepository;
import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.LiquidationEvent;
import com.kwikquant.shared.types.MarginMode;
import com.kwikquant.shared.types.MarketType;
import com.kwikquant.shared.types.OrderSide;
import com.kwikquant.shared.types.OrderStatus;
import com.kwikquant.shared.types.OrderType;
import com.kwikquant.shared.types.PositionEffect;
import com.kwikquant.trading.domain.Fill;
import com.kwikquant.trading.domain.Order;
import com.kwikquant.trading.domain.Position;
import com.kwikquant.trading.infrastructure.ConcurrencyConflictException;
import com.kwikquant.trading.infrastructure.FillMapper;
import com.kwikquant.trading.infrastructure.OrderMapper;
import com.kwikquant.trading.interfaces.OrderWebSocketBroadcaster;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 阶段2e:ExecutionService.processLiquidation 五步事务单元测试。
 *
 * <p>覆盖 spec §11 M6-new + §12 B3-s/M4-s 五步流程:
 * <ol>
 *   <li>PositionService.applyFill(PERP, CLOSE_*, leverage, marginMode) → realizedPnlDelta(含 CAS 重试)</li>
 *   <li>BalanceService.applyLiquidationDelta(clamp 0)</li>
 *   <li>OrderMapper.insert(系统强平 Order, status=FILLED, 绕过 validate + 状态机)</li>
 *   <li>FillMapper.insert(Fill, externalFillId="liq-{positionId}-{millis}")</li>
 *   <li>AuditRepository.save(AuditEntry action=LIQUIDATION) + afterCommit publish LiquidationEvent</li>
 * </ol>
 *
 * <p>CAS 冲突回滚路径(用例 3):applyFill 抛 ConcurrencyConflictException → 事务回滚,后续步骤全不调,
 * audit 也不记(强平幂等,下 tick 再判)。审计与事务同库同事务,事务回滚 audit 也回滚。
 */
class ExecutionServiceProcessLiquidationTest {

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
        orderMapper = org.mockito.Mockito.mock(OrderMapper.class);
        fillMapper = org.mockito.Mockito.mock(FillMapper.class);
        positionService = org.mockito.Mockito.mock(PositionService.class);
        wsBroadcaster = org.mockito.Mockito.mock(OrderWebSocketBroadcaster.class);
        accountService = org.mockito.Mockito.mock(ExchangeAccountService.class);
        balanceService = org.mockito.Mockito.mock(BalanceService.class);
        auditRepository = org.mockito.Mockito.mock(AuditRepository.class);
        eventPublisher = org.mockito.Mockito.mock(ApplicationEventPublisher.class);
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

    // ---------- 用例 1:多头强平,五步全跑 ----------

    @Test
    void processLiquidation_longPosition_markPriceBelowLiq_allFiveSteps() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            // LONG, qty=0.1, avgEntry=42000, liq=37800, frozen=420, leverage=10, ISOLATED
            Position pos = position(
                    100L,
                    Position.SIDE_LONG,
                    "LONG",
                    new BigDecimal("0.1"),
                    new BigDecimal("42000"),
                    new BigDecimal("37800"),
                    new BigDecimal("420"),
                    10,
                    MarginMode.ISOLATED,
                    1L,
                    "BTC/USDT");
            when(positionService.findById(100L)).thenReturn(pos);
            // realizedPnlDelta = (markPrice - avgEntry) * qty = (37000 - 42000) * 0.1 = -700
            BigDecimal pnlDelta = new BigDecimal("-700");
            when(positionService.applyFill(
                            eq(1L),
                            eq("BTC/USDT"),
                            eq(OrderSide.SELL),
                            eq(new BigDecimal("0.1")),
                            eq(new BigDecimal("37000")),
                            eq(BigDecimal.ZERO),
                            eq(MarketType.PERP),
                            eq(PositionEffect.CLOSE_LONG),
                            eq(10),
                            eq(MarginMode.ISOLATED)))
                    .thenReturn(pnlDelta);
            ExchangeAccount acct = paperAccount(1L, 42L, Exchange.OKX);
            when(accountService.findById(1L)).thenReturn(acct);
            // orderMapper.insert 模拟 useGeneratedKeys 回填 id
            org.mockito.Mockito.doAnswer(inv -> {
                        Order o = inv.getArgument(0);
                        o.setId(999L);
                        return null;
                    })
                    .when(orderMapper)
                    .insert(any(Order.class));

            service.processLiquidation(100L, new BigDecimal("37000"), null);

            // 步骤 1:applyFill(CLOSE_LONG, SELL, qty=0.1, price=37000, fee=ZERO, PERP) 调一次
            verify(positionService)
                    .applyFill(
                            eq(1L),
                            eq("BTC/USDT"),
                            eq(OrderSide.SELL),
                            eq(new BigDecimal("0.1")),
                            eq(new BigDecimal("37000")),
                            eq(BigDecimal.ZERO),
                            eq(MarketType.PERP),
                            eq(PositionEffect.CLOSE_LONG),
                            eq(10),
                            eq(MarginMode.ISOLATED));

            // 步骤 2:applyLiquidationDelta(accountId, paperTrading=true, currency="USDT", -700, -700)
            verify(balanceService)
                    .applyLiquidationDelta(
                            eq(1L), eq(true), eq("USDT"), eq(new BigDecimal("-700")), eq(new BigDecimal("-700")));

            // 步骤 3:orderMapper.insert(系统 Order, status=FILLED, positionEffect=CLOSE_LONG, side=SELL,
            //                            amount=0.1, price=37000, filledQty=0.1, filledAvgPrice=37000, marketType=PERP)
            ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
            verify(orderMapper).insert(orderCaptor.capture());
            Order sysOrder = orderCaptor.getValue();
            assertThat(sysOrder.getStatus()).isEqualTo(OrderStatus.FILLED);
            assertThat(sysOrder.getMarketType()).isEqualTo(MarketType.PERP);
            assertThat(sysOrder.getSide()).isEqualTo(OrderSide.SELL);
            assertThat(sysOrder.getOrderType()).isEqualTo(OrderType.MARKET);
            assertThat(sysOrder.getPositionEffect()).isEqualTo(PositionEffect.CLOSE_LONG);
            assertThat(sysOrder.getAmount()).isEqualByComparingTo("0.1");
            assertThat(sysOrder.getPrice()).isEqualByComparingTo("37000");
            assertThat(sysOrder.getFilledQty()).isEqualByComparingTo("0.1");
            assertThat(sysOrder.getFilledAvgPrice()).isEqualByComparingTo("37000");
            assertThat(sysOrder.getLeverage()).isEqualTo(10);
            assertThat(sysOrder.getMarginMode()).isEqualTo(MarginMode.ISOLATED);
            assertThat(sysOrder.getAccountId()).isEqualTo(1L);
            assertThat(sysOrder.getSymbol()).isEqualTo("BTC/USDT");
            assertThat(sysOrder.getExchange()).isEqualTo(Exchange.OKX);
            assertThat(sysOrder.getVersion()).isEqualTo(0L);

            // 步骤 4:fillMapper.insert(Fill, externalFillId starts "liq-", orderId=999)
            ArgumentCaptor<Fill> fillCaptor = ArgumentCaptor.forClass(Fill.class);
            verify(fillMapper).insert(fillCaptor.capture());
            Fill fill = fillCaptor.getValue();
            assertThat(fill.getOrderId()).isEqualTo(999L);
            assertThat(fill.getExternalFillId()).startsWith("liq-100-");
            assertThat(fill.getSide()).isEqualTo(OrderSide.SELL);
            assertThat(fill.getPrice()).isEqualByComparingTo("37000");
            assertThat(fill.getQty()).isEqualByComparingTo("0.1");
            assertThat(fill.getFee()).isEqualByComparingTo("0");
            assertThat(fill.getLiquidity()).isEqualTo("taker");
            assertThat(fill.getFeeCurrency()).isEqualTo("USDT");

            // 步骤 5:auditRepository.save(AuditEntry action=LIQUIDATION, targetType=POSITION, targetId=100)
            ArgumentCaptor<AuditEntry> auditCaptor = ArgumentCaptor.forClass(AuditEntry.class);
            verify(auditRepository).save(auditCaptor.capture());
            AuditEntry entry = auditCaptor.getValue();
            assertThat(entry.action()).isEqualTo("LIQUIDATION");
            assertThat(entry.targetType()).isEqualTo("POSITION");
            assertThat(entry.targetId()).isEqualTo("100");
            assertThat(entry.status()).isEqualTo(AuditEntry.STATUS_SUCCESS);
            assertThat(entry.actorUserId()).isEqualTo("system");
            assertThat(entry.metadata()).containsKey("positionId");
            assertThat(entry.metadata()).containsKey("systemOrderId");
            assertThat(entry.metadata().get("positionId")).isEqualTo(100L);

            // afterCommit:publishEvent(LiquidationEvent) — 触发同步回调
            simulateAfterCommit();
            ArgumentCaptor<LiquidationEvent> evCaptor = ArgumentCaptor.forClass(LiquidationEvent.class);
            verify(eventPublisher).publishEvent(evCaptor.capture());
            LiquidationEvent ev = evCaptor.getValue();
            assertThat(ev.orderId()).isNull();
            assertThat(ev.positionId()).isEqualTo(100L);
            assertThat(ev.positionSide()).isEqualTo("LONG");
            assertThat(ev.leverage()).isEqualTo(10);
            assertThat(ev.liquidationPrice()).isEqualByComparingTo("37800");
            assertThat(ev.markPrice()).isEqualByComparingTo("37000");
            assertThat(ev.realizedPnl()).isEqualByComparingTo("-700");
            assertThat(ev.reason()).contains("37000");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    // ---------- 用例 2:空头强平,五步全跑 ----------

    @Test
    void processLiquidation_shortPosition_markPriceAboveLiq_allFiveSteps() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            // SHORT, qty=0.1, avgEntry=42000, liq=46200, frozen=420, leverage=10, ISOLATED
            Position pos = position(
                    200L,
                    Position.SIDE_SHORT,
                    "SHORT",
                    new BigDecimal("0.1"),
                    new BigDecimal("42000"),
                    new BigDecimal("46200"),
                    new BigDecimal("420"),
                    10,
                    MarginMode.ISOLATED,
                    2L,
                    "ETH/USDT");
            when(positionService.findById(200L)).thenReturn(pos);
            // realizedPnlDelta = (avgEntry - markPrice) * qty = (42000 - 47000) * 0.1 = -500
            BigDecimal pnlDelta = new BigDecimal("-500");
            when(positionService.applyFill(
                            eq(2L),
                            eq("ETH/USDT"),
                            eq(OrderSide.BUY),
                            eq(new BigDecimal("0.1")),
                            eq(new BigDecimal("47000")),
                            eq(BigDecimal.ZERO),
                            eq(MarketType.PERP),
                            eq(PositionEffect.CLOSE_SHORT),
                            eq(10),
                            eq(MarginMode.ISOLATED)))
                    .thenReturn(pnlDelta);
            ExchangeAccount acct = paperAccount(2L, 88L, Exchange.BINANCE);
            when(accountService.findById(2L)).thenReturn(acct);
            org.mockito.Mockito.doAnswer(inv -> {
                        Order o = inv.getArgument(0);
                        o.setId(888L);
                        return null;
                    })
                    .when(orderMapper)
                    .insert(any(Order.class));

            service.processLiquidation(200L, new BigDecimal("47000"), null);

            // 验证派生方向:CLOSE_SHORT → side=BUY
            verify(positionService)
                    .applyFill(
                            eq(2L),
                            eq("ETH/USDT"),
                            eq(OrderSide.BUY),
                            any(),
                            any(),
                            any(),
                            eq(MarketType.PERP),
                            eq(PositionEffect.CLOSE_SHORT),
                            eq(10),
                            eq(MarginMode.ISOLATED));
            verify(balanceService)
                    .applyLiquidationDelta(
                            eq(2L), eq(true), eq("USDT"), eq(new BigDecimal("-500")), eq(new BigDecimal("-500")));

            ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
            verify(orderMapper).insert(orderCaptor.capture());
            Order sysOrder = orderCaptor.getValue();
            assertThat(sysOrder.getSide()).isEqualTo(OrderSide.BUY);
            assertThat(sysOrder.getPositionEffect()).isEqualTo(PositionEffect.CLOSE_SHORT);
            assertThat(sysOrder.getExchange()).isEqualTo(Exchange.BINANCE);

            ArgumentCaptor<Fill> fillCaptor = ArgumentCaptor.forClass(Fill.class);
            verify(fillMapper).insert(fillCaptor.capture());
            Fill fill = fillCaptor.getValue();
            assertThat(fill.getExternalFillId()).startsWith("liq-200-");
            assertThat(fill.getSide()).isEqualTo(OrderSide.BUY);
            assertThat(fill.getFeeCurrency()).isEqualTo("USDT");

            verify(auditRepository).save(any(AuditEntry.class));

            simulateAfterCommit();
            ArgumentCaptor<LiquidationEvent> evCaptor = ArgumentCaptor.forClass(LiquidationEvent.class);
            verify(eventPublisher).publishEvent(evCaptor.capture());
            LiquidationEvent ev = evCaptor.getValue();
            assertThat(ev.positionId()).isEqualTo(200L);
            assertThat(ev.positionSide()).isEqualTo("SHORT");
            assertThat(ev.liquidationPrice()).isEqualByComparingTo("46200");
            assertThat(ev.markPrice()).isEqualByComparingTo("47000");
            assertThat(ev.realizedPnl()).isEqualByComparingTo("-500");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    // ---------- 用例 3:applyFill CAS 冲突 → 抛 + 回滚,后续步骤不调 ----------

    @Test
    void processLiquidation_applyFillConcurrencyConflict_throwsAndRollsBack() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            Position pos = position(
                    300L,
                    Position.SIDE_LONG,
                    "LONG",
                    new BigDecimal("0.1"),
                    new BigDecimal("42000"),
                    new BigDecimal("37800"),
                    new BigDecimal("420"),
                    10,
                    MarginMode.ISOLATED,
                    3L,
                    "BTC/USDT");
            when(positionService.findById(300L)).thenReturn(pos);
            when(positionService.applyFill(anyLong(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                    .thenThrow(new ConcurrencyConflictException("CAS failed"));
            ExchangeAccount acct = paperAccount(3L, 7L, Exchange.OKX);
            when(accountService.findById(3L)).thenReturn(acct);

            assertThatThrownBy(() -> service.processLiquidation(300L, new BigDecimal("37000"), null))
                    .isInstanceOf(ConcurrencyConflictException.class);

            // 回滚前未到这些步骤(applyFill 在第一步)
            verify(orderMapper, never()).insert(any());
            verify(fillMapper, never()).insert(any());
            verify(auditRepository, never()).save(any());
            verify(balanceService, never()).applyLiquidationDelta(anyLong(), anyBoolean(), any(), any(), any());
            // afterCommit 不应触发(事务回滚)
            simulateAfterCommit();
            verify(eventPublisher, never()).publishEvent(any());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    // ---------- 用例 4:triggerOrderId 传入 → LiquidationEvent.orderId 使用 ----------

    @Test
    void processLiquidation_triggerOrderIdPassed_usesInEvent() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            Position pos = position(
                    400L,
                    Position.SIDE_LONG,
                    "LONG",
                    new BigDecimal("0.1"),
                    new BigDecimal("42000"),
                    new BigDecimal("37800"),
                    new BigDecimal("420"),
                    10,
                    MarginMode.ISOLATED,
                    4L,
                    "BTC/USDT");
            when(positionService.findById(400L)).thenReturn(pos);
            when(positionService.applyFill(anyLong(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(new BigDecimal("-700"));
            when(accountService.findById(4L)).thenReturn(paperAccount(4L, 42L, Exchange.OKX));
            org.mockito.Mockito.doAnswer(inv -> {
                        Order o = inv.getArgument(0);
                        o.setId(777L);
                        return null;
                    })
                    .when(orderMapper)
                    .insert(any(Order.class));

            service.processLiquidation(400L, new BigDecimal("37000"), 999L);

            // audit metadata 含 triggerOrderId=999
            ArgumentCaptor<AuditEntry> auditCaptor = ArgumentCaptor.forClass(AuditEntry.class);
            verify(auditRepository).save(auditCaptor.capture());
            assertThat(auditCaptor.getValue().metadata()).containsEntry("triggerOrderId", 999L);

            simulateAfterCommit();
            ArgumentCaptor<LiquidationEvent> evCaptor = ArgumentCaptor.forClass(LiquidationEvent.class);
            verify(eventPublisher).publishEvent(evCaptor.capture());
            assertThat(evCaptor.getValue().orderId()).isEqualTo(999L);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    // ---------- 用例 5:triggerOrderId=null → LiquidationEvent.orderId=null ----------

    @Test
    void processLiquidation_triggerOrderIdNull_eventOrderIdNull() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            Position pos = position(
                    500L,
                    Position.SIDE_LONG,
                    "LONG",
                    new BigDecimal("0.1"),
                    new BigDecimal("42000"),
                    new BigDecimal("37800"),
                    new BigDecimal("420"),
                    10,
                    MarginMode.ISOLATED,
                    5L,
                    "BTC/USDT");
            when(positionService.findById(500L)).thenReturn(pos);
            when(positionService.applyFill(anyLong(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(new BigDecimal("-700"));
            when(accountService.findById(5L)).thenReturn(paperAccount(5L, 42L, Exchange.OKX));
            org.mockito.Mockito.doAnswer(inv -> {
                        Order o = inv.getArgument(0);
                        o.setId(666L);
                        return null;
                    })
                    .when(orderMapper)
                    .insert(any(Order.class));

            service.processLiquidation(500L, new BigDecimal("37000"), null);

            simulateAfterCommit();
            ArgumentCaptor<LiquidationEvent> evCaptor = ArgumentCaptor.forClass(LiquidationEvent.class);
            verify(eventPublisher).publishEvent(evCaptor.capture());
            LiquidationEvent ev = evCaptor.getValue();
            assertThat(ev.orderId()).isNull();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    // ---------- 用例 6:负余额 clamp 由 applyLiquidationDelta 处理,processLiquidation 只透传 delta ----------

    @Test
    void processLiquidation_negativeBalanceClamp_applyLiquidationDeltaHandles() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            Position pos = position(
                    600L,
                    Position.SIDE_LONG,
                    "LONG",
                    new BigDecimal("0.1"),
                    new BigDecimal("42000"),
                    new BigDecimal("37800"),
                    new BigDecimal("420"),
                    10,
                    MarginMode.ISOLATED,
                    6L,
                    "BTC/USDT");
            when(positionService.findById(600L)).thenReturn(pos);
            when(positionService.applyFill(anyLong(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(new BigDecimal("-700"));
            when(accountService.findById(6L)).thenReturn(paperAccount(6L, 42L, Exchange.OKX));
            org.mockito.Mockito.doAnswer(inv -> {
                        Order o = inv.getArgument(0);
                        o.setId(555L);
                        return null;
                    })
                    .when(orderMapper)
                    .insert(any(Order.class));

            service.processLiquidation(600L, new BigDecimal("37000"), null);

            // clamp 逻辑在 PaperBalanceAdapterTest 已测,这里只验证透传的 delta 与 pnlDelta 一致
            verify(balanceService)
                    .applyLiquidationDelta(
                            eq(6L), eq(true), eq("USDT"), eq(new BigDecimal("-700")), eq(new BigDecimal("-700")));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    // ---------- helpers ----------

    /** 触发已注册的 afterCommit 回调,模拟事务提交。 */
    private void simulateAfterCommit() {
        for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
            sync.afterCommit();
        }
    }

    private Position position(
            long id,
            String side,
            String positionSide,
            BigDecimal qty,
            BigDecimal avgEntry,
            BigDecimal liqPrice,
            BigDecimal frozen,
            int leverage,
            MarginMode marginMode,
            long accountId,
            String symbol) {
        Position p = new Position();
        p.setId(id);
        p.setSide(side);
        p.setPositionSide(positionSide);
        p.setQty(qty);
        p.setAvgEntryPrice(avgEntry);
        p.setLiquidationPrice(liqPrice);
        p.setFrozenAmount(frozen);
        p.setLeverage(leverage);
        p.setMarginMode(marginMode);
        p.setAccountId(accountId);
        p.setSymbol(symbol);
        p.setVersion(1L);
        return p;
    }

    private ExchangeAccount paperAccount(long accountId, long userId, Exchange exchange) {
        ExchangeAccount acct = new ExchangeAccount();
        acct.setId(accountId);
        acct.setUserId(userId);
        acct.setExchange(exchange);
        acct.setPaperTrading(true);
        return acct;
    }

    @SuppressWarnings("unused")
    private static Map<String, Object> meta() {
        return Map.of();
    }

    @SuppressWarnings("unused")
    private static ArgumentCaptor<Object> unused() {
        return ArgumentCaptor.forClass(Object.class);
    }

    @SuppressWarnings("unused")
    private static ArgumentMatchers ArgumentMatchers() {
        return null;
    }
}
