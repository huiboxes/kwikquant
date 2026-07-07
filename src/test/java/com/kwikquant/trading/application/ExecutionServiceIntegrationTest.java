package com.kwikquant.trading.application;

import static org.assertj.core.api.Assertions.*;

import com.kwikquant.AbstractIntegrationTest;
import com.kwikquant.KwikquantApplication;
import com.kwikquant.market.domain.TradingPairInfo;
import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.MarketType;
import com.kwikquant.shared.types.OrderSide;
import com.kwikquant.shared.types.OrderStatus;
import com.kwikquant.shared.types.OrderType;
import com.kwikquant.trading.domain.Order;
import com.kwikquant.trading.domain.OrderSubmitCommand;
import com.kwikquant.trading.domain.TimeInForce;
import com.kwikquant.trading.infrastructure.FillMapper;
import com.kwikquant.trading.infrastructure.OrderMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest(classes = KwikquantApplication.class)
@TestPropertySource(
        properties = {
            "JWT_SECRET=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
            "ENCRYPTION_KEY=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
        })
class ExecutionServiceIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    ExecutionService executionService;

    @Autowired
    OrderMapper orderMapper;

    @Autowired
    FillMapper fillMapper;

    private static TradingPairInfo pair() {
        return new TradingPairInfo(
                Exchange.BINANCE,
                MarketType.SPOT,
                "BTC/USDT",
                "BTC",
                "USDT",
                new BigDecimal("0.0001"),
                new BigDecimal("100"),
                new BigDecimal("0.01"),
                new BigDecimal("0.00000001"),
                true);
    }

    private static long uniqueAccountId() {
        return System.nanoTime() % 10_000_000L;
    }

    private Order seedSubmittedOrder(long acct) {
        OrderSubmitCommand cmd = new OrderSubmitCommand(
                acct,
                "BTC/USDT",
                MarketType.SPOT,
                OrderSide.BUY,
                OrderType.LIMIT,
                new BigDecimal("0.1"),
                new BigDecimal("42000"),
                null,
                TimeInForce.GTC,
                null,
                "client-" + acct);
        Order o = Order.create(cmd, pair());
        o.setReferenceExchange(Exchange.BINANCE);
        orderMapper.insert(o);
        o.transitionTo(OrderStatus.PENDING_NEW);
        orderMapper.casUpdate(o);
        o.setVersion(o.getVersion() + 1);
        o.transitionTo(OrderStatus.SUBMITTED);
        orderMapper.casUpdate(o);
        o.setVersion(o.getVersion() + 1);
        return o;
    }

    @Test
    void processFullFillTransitionsToFilled() {
        long acct = uniqueAccountId();
        Order o = seedSubmittedOrder(acct);

        executionService.processExecutionReport(new ExecutionReport(
                o.getId(),
                UUID.randomUUID().toString(),
                new BigDecimal("42000"),
                new BigDecimal("0.1"),
                new BigDecimal("4.2"),
                "USDT",
                "maker",
                Instant.now()));

        Order reloaded = orderMapper.findById(o.getId());
        assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.FILLED);
        assertThat(reloaded.getFilledQty()).isEqualByComparingTo("0.1");
    }

    @Test
    void processPartialFillTransitionsToPartiallyFilled() {
        long acct = uniqueAccountId();
        Order o = seedSubmittedOrder(acct);

        executionService.processExecutionReport(new ExecutionReport(
                o.getId(),
                UUID.randomUUID().toString(),
                new BigDecimal("42000"),
                new BigDecimal("0.05"),
                new BigDecimal("2.1"),
                "USDT",
                "maker",
                Instant.now()));

        Order reloaded = orderMapper.findById(o.getId());
        assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.PARTIALLY_FILLED);
        assertThat(reloaded.getFilledQty()).isEqualByComparingTo("0.05");
    }

    @Test
    void idempotentDuplicateReportNoOp() {
        long acct = uniqueAccountId();
        Order o = seedSubmittedOrder(acct);
        String extId = UUID.randomUUID().toString();

        executionService.processExecutionReport(new ExecutionReport(
                o.getId(),
                extId,
                new BigDecimal("42000"),
                new BigDecimal("0.05"),
                new BigDecimal("2.1"),
                "USDT",
                "maker",
                Instant.now()));

        Order afterFirst = orderMapper.findById(o.getId());
        long versionAfterFirst = afterFirst.getVersion();

        // 重发同一 external_fill_id
        executionService.processExecutionReport(new ExecutionReport(
                o.getId(),
                extId,
                new BigDecimal("42000"),
                new BigDecimal("0.05"),
                new BigDecimal("2.1"),
                "USDT",
                "maker",
                Instant.now()));

        Order afterSecond = orderMapper.findById(o.getId());
        assertThat(afterSecond.getFilledQty()).isEqualByComparingTo("0.05");
        assertThat(afterSecond.getVersion()).isEqualTo(versionAfterFirst);
    }

    @Test
    void onExchangeAcceptedTransitions() {
        long acct = uniqueAccountId();
        OrderSubmitCommand cmd = new OrderSubmitCommand(
                acct,
                "BTC/USDT",
                MarketType.SPOT,
                OrderSide.BUY,
                OrderType.LIMIT,
                new BigDecimal("0.1"),
                new BigDecimal("42000"),
                null,
                TimeInForce.GTC,
                null,
                "client-" + acct);
        Order o = Order.create(cmd, pair());
        o.setReferenceExchange(Exchange.BINANCE);
        orderMapper.insert(o);

        executionService.onExchangeAccepted(o.getId(), "EXCH-123");

        Order reloaded = orderMapper.findById(o.getId());
        assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.SUBMITTED);
        assertThat(reloaded.getExchangeOrderId()).isEqualTo("EXCH-123");
    }

    @Test
    void onExchangeRejectedTransitions() {
        long acct = uniqueAccountId();
        OrderSubmitCommand cmd = new OrderSubmitCommand(
                acct,
                "BTC/USDT",
                MarketType.SPOT,
                OrderSide.BUY,
                OrderType.LIMIT,
                new BigDecimal("0.1"),
                new BigDecimal("42000"),
                null,
                TimeInForce.GTC,
                null,
                "client-" + acct);
        Order o = Order.create(cmd, pair());
        o.setReferenceExchange(Exchange.BINANCE);
        orderMapper.insert(o);

        executionService.onExchangeRejected(o.getId(), "insufficient balance");

        Order reloaded = orderMapper.findById(o.getId());
        assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.REJECTED);
    }

    @Test
    void processFill_whenTerminalOrder_skipsProcessing() {
        long acct = uniqueAccountId();
        Order o = seedSubmittedOrder(acct);
        // First fill → FILLED
        executionService.processExecutionReport(new ExecutionReport(
                o.getId(),
                UUID.randomUUID().toString(),
                new BigDecimal("42000"),
                new BigDecimal("0.1"),
                BigDecimal.ZERO,
                "USDT",
                "maker",
                Instant.now()));
        assertThat(orderMapper.findById(o.getId()).getStatus()).isEqualTo(OrderStatus.FILLED);

        // Second fill on terminal order → should be skipped (no exception, no state change)
        long versionAfterFirst = orderMapper.findById(o.getId()).getVersion();
        executionService.processExecutionReport(new ExecutionReport(
                o.getId(),
                UUID.randomUUID().toString(),
                new BigDecimal("42000"),
                new BigDecimal("0.1"),
                BigDecimal.ZERO,
                "USDT",
                "maker",
                Instant.now()));
        assertThat(orderMapper.findById(o.getId()).getVersion()).isEqualTo(versionAfterFirst);
    }

    @Test
    void processFill_withNullExternalFillId_skipsIdempotencyCheck() {
        long acct = uniqueAccountId();
        Order o = seedSubmittedOrder(acct);

        // null externalFillId → idempotency check skipped, fill still processed
        executionService.processExecutionReport(new ExecutionReport(
                o.getId(),
                null,
                new BigDecimal("42000"),
                new BigDecimal("0.1"),
                BigDecimal.ZERO,
                "USDT",
                "maker",
                Instant.now()));

        Order reloaded = orderMapper.findById(o.getId());
        assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.FILLED);
    }

    @Test
    void processFill_writesFillRecord() {
        long acct = uniqueAccountId();
        Order o = seedSubmittedOrder(acct);

        executionService.processExecutionReport(new ExecutionReport(
                o.getId(),
                "ext-fill-1",
                new BigDecimal("42000"),
                new BigDecimal("0.1"),
                new BigDecimal("4.2"),
                "USDT",
                "maker",
                Instant.now()));

        // Verify fill was persisted (covers fillMapper.insert + positionService.applyFill)
        Order reloaded = orderMapper.findById(o.getId());
        assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.FILLED);
        assertThat(reloaded.getFilledAvgPrice()).isEqualByComparingTo("42000");
    }

    @Test
    void onExchangeAccepted_whenAlreadySubmitted_noOp() {
        long acct = uniqueAccountId();
        Order o = seedSubmittedOrder(acct); // already SUBMITTED

        // Calling onExchangeAccepted on already-SUBMITTED order should not throw
        executionService.onExchangeAccepted(o.getId(), "EXCH-456");

        Order reloaded = orderMapper.findById(o.getId());
        assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.SUBMITTED);
    }

    /**
     * 核心竞态测试：order 已被 cancel 推到 PENDING_CANCEL，此时 fill 到达。
     * 验证：fill 必须持久化（交易所真实成交不能丢），filledQty 必须更新，
     * 但 order 状态保持 PENDING_CANCEL（等 cancel 结果决定最终态）。
     */
    @Test
    void processFill_whenOrderInPendingCancel_persistsFillWithoutStatusChange() {
        long acct = uniqueAccountId();
        Order o = seedSubmittedOrder(acct);

        // 模拟并发 cancel：手动推到 PENDING_CANCEL
        o.transitionTo(OrderStatus.PENDING_CANCEL);
        orderMapper.casUpdate(o);
        o.setVersion(o.getVersion() + 1);

        // 确认前置状态
        Order before = orderMapper.findById(o.getId());
        assertThat(before.getStatus()).isEqualTo(OrderStatus.PENDING_CANCEL);
        assertThat(before.getFilledQty()).isEqualByComparingTo("0");

        // fill 到达（partial: 0.05 out of 0.1）
        String extFillId = UUID.randomUUID().toString();
        executionService.processExecutionReport(new ExecutionReport(
                o.getId(),
                extFillId,
                new BigDecimal("42000"),
                new BigDecimal("0.05"),
                new BigDecimal("2.1"),
                "USDT",
                "maker",
                Instant.now()));

        // 验证：order 状态保持 PENDING_CANCEL
        Order after = orderMapper.findById(o.getId());
        assertThat(after.getStatus()).isEqualTo(OrderStatus.PENDING_CANCEL);

        // 验证：filledQty 已更新（fill 没有丢）
        assertThat(after.getFilledQty()).isEqualByComparingTo("0.05");
        assertThat(after.getFilledAvgPrice()).isEqualByComparingTo("42000");

        // 验证：fill 记录已持久化
        assertThat(fillMapper.existsByExternalFillId(acct, extFillId)).isTrue();
    }

    /**
     * 全成 fill 在 PENDING_CANCEL 状态下应直接推到 FILLED（合法转换）。
     */
    @Test
    void processFullFill_whenOrderInPendingCancel_transitionsToFilled() {
        long acct = uniqueAccountId();
        Order o = seedSubmittedOrder(acct);

        // 模拟并发 cancel
        o.transitionTo(OrderStatus.PENDING_CANCEL);
        orderMapper.casUpdate(o);
        o.setVersion(o.getVersion() + 1);

        // full fill 到达
        executionService.processExecutionReport(new ExecutionReport(
                o.getId(),
                UUID.randomUUID().toString(),
                new BigDecimal("42000"),
                new BigDecimal("0.1"),
                new BigDecimal("4.2"),
                "USDT",
                "maker",
                Instant.now()));

        // PENDING_CANCEL → FILLED 是合法转换，应成功推进
        Order after = orderMapper.findById(o.getId());
        assertThat(after.getStatus()).isEqualTo(OrderStatus.FILLED);
        assertThat(after.getFilledQty()).isEqualByComparingTo("0.1");
    }

    /**
     * 两笔 fill 先后到达，第二笔在 PENDING_CANCEL 状态下处理。
     * 验证累计 filledQty 正确 + fill 都持久化。
     */
    @Test
    void processMultipleFills_secondDuringPendingCancel_allPersisted() {
        long acct = uniqueAccountId();
        Order o = seedSubmittedOrder(acct);

        // 第一笔 partial fill（正常 SUBMITTED 状态）
        executionService.processExecutionReport(new ExecutionReport(
                o.getId(),
                UUID.randomUUID().toString(),
                new BigDecimal("42000"),
                new BigDecimal("0.03"),
                new BigDecimal("1.26"),
                "USDT",
                "maker",
                Instant.now()));

        Order afterFirst = orderMapper.findById(o.getId());
        assertThat(afterFirst.getStatus()).isEqualTo(OrderStatus.PARTIALLY_FILLED);
        assertThat(afterFirst.getFilledQty()).isEqualByComparingTo("0.03");

        // 并发 cancel 推到 PENDING_CANCEL
        afterFirst.transitionTo(OrderStatus.PENDING_CANCEL);
        orderMapper.casUpdate(afterFirst);

        // 第二笔 partial fill（PENDING_CANCEL 状态）
        String extFillId2 = UUID.randomUUID().toString();
        executionService.processExecutionReport(new ExecutionReport(
                o.getId(),
                extFillId2,
                new BigDecimal("42100"),
                new BigDecimal("0.04"),
                new BigDecimal("1.684"),
                "USDT",
                "taker",
                Instant.now()));

        // 验证：状态保持 PENDING_CANCEL，filledQty 累加
        Order afterSecond = orderMapper.findById(o.getId());
        assertThat(afterSecond.getStatus()).isEqualTo(OrderStatus.PENDING_CANCEL);
        assertThat(afterSecond.getFilledQty()).isEqualByComparingTo("0.07");

        // 验证：两笔 fill 都持久化
        assertThat(fillMapper.findByOrderId(o.getId())).hasSize(2);
    }
}
