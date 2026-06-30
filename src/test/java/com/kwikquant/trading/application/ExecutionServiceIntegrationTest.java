package com.kwikquant.trading.application;

import static org.assertj.core.api.Assertions.*;

import com.kwikquant.AbstractIntegrationTest;
import com.kwikquant.KwikquantApplication;
import com.kwikquant.shared.types.MarketType;
import com.kwikquant.shared.types.OrderSide;
import com.kwikquant.shared.types.OrderStatus;
import com.kwikquant.shared.types.OrderType;
import com.kwikquant.trading.domain.Order;
import com.kwikquant.trading.domain.OrderSubmitCommand;
import com.kwikquant.trading.domain.TimeInForce;
import com.kwikquant.trading.infrastructure.OrderMapper;
import com.kwikquant.market.domain.TradingPairInfo;
import com.kwikquant.shared.types.Exchange;
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
                o.getId(), extId, new BigDecimal("42000"), new BigDecimal("0.05"), new BigDecimal("2.1"),
                "USDT", "maker", Instant.now()));

        Order afterFirst = orderMapper.findById(o.getId());
        long versionAfterFirst = afterFirst.getVersion();

        // 重发同一 external_fill_id
        executionService.processExecutionReport(new ExecutionReport(
                o.getId(), extId, new BigDecimal("42000"), new BigDecimal("0.05"), new BigDecimal("2.1"),
                "USDT", "maker", Instant.now()));

        Order afterSecond = orderMapper.findById(o.getId());
        assertThat(afterSecond.getFilledQty()).isEqualByComparingTo("0.05");
        assertThat(afterSecond.getVersion()).isEqualTo(versionAfterFirst);
    }

    @Test
    void onExchangeAcceptedTransitions() {
        long acct = uniqueAccountId();
        OrderSubmitCommand cmd = new OrderSubmitCommand(
                acct, "BTC/USDT", MarketType.SPOT, OrderSide.BUY, OrderType.LIMIT,
                new BigDecimal("0.1"), new BigDecimal("42000"), null,
                TimeInForce.GTC, null, "client-" + acct);
        Order o = Order.create(cmd, pair());
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
                acct, "BTC/USDT", MarketType.SPOT, OrderSide.BUY, OrderType.LIMIT,
                new BigDecimal("0.1"), new BigDecimal("42000"), null,
                TimeInForce.GTC, null, "client-" + acct);
        Order o = Order.create(cmd, pair());
        orderMapper.insert(o);

        executionService.onExchangeRejected(o.getId(), "insufficient balance");

        Order reloaded = orderMapper.findById(o.getId());
        assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.REJECTED);
    }
}
