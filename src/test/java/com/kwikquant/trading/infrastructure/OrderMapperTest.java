package com.kwikquant.trading.infrastructure;

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
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
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
class OrderMapperTest extends AbstractIntegrationTest {

    @Autowired
    OrderMapper orderMapper;

    private static long uniqueAccountId() {
        return System.nanoTime() % 10_000_000L;
    }

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

    /** Batch 6a: 订单持久化 reference_exchange(不可变,创建时定)。helper 默认 BINANCE。 */
    private static Order limitBuyOrder(long accountId, String price, TimeInForce tif, Instant expireAt) {
        Order o = baseLimitBuyOrder(accountId, price, tif, expireAt);
        o.setExchange(Exchange.BINANCE);
        return o;
    }

    private static Order baseLimitBuyOrder(long accountId, String price, TimeInForce tif, Instant expireAt) {
        OrderSubmitCommand cmd = new OrderSubmitCommand(
                accountId,
                "BTC/USDT",
                MarketType.SPOT,
                OrderSide.BUY,
                OrderType.LIMIT,
                new BigDecimal("0.1"),
                new BigDecimal(price),
                null,
                tif,
                expireAt,
                "client-" + System.nanoTime());
        return Order.create(cmd, pair());
    }

    /** 模拟 service 层的 CAS 模式：成功后手动 +1。 */
    private void cas(Order o) {
        int affected = orderMapper.casUpdate(o);
        if (affected == 1) {
            o.setVersion(o.getVersion() + 1);
        }
    }

    @Test
    void insertAndFindById() {
        long acct = uniqueAccountId();
        Order o = limitBuyOrder(acct, "42000.00", TimeInForce.GTC, null);
        orderMapper.insert(o);
        assertThat(o.getId()).isNotNull();

        Order loaded = orderMapper.findById(o.getId());
        assertThat(loaded).isNotNull();
        assertThat(loaded.getAccountId()).isEqualTo(acct);
        assertThat(loaded.getSymbol()).isEqualTo("BTC/USDT");
        assertThat(loaded.getSide()).isEqualTo(OrderSide.BUY);
        assertThat(loaded.getOrderType()).isEqualTo(OrderType.LIMIT);
        assertThat(loaded.getAmount()).isEqualByComparingTo("0.1");
        assertThat(loaded.getPrice()).isEqualByComparingTo("42000.00");
        assertThat(loaded.getTimeInForce()).isEqualTo(TimeInForce.GTC);
        assertThat(loaded.getStatus()).isEqualTo(OrderStatus.NEW);
        assertThat(loaded.getVersion()).isZero();
        // Batch 6a: reference_exchange round-trips(helper 默认 BINANCE)
        assertThat(loaded.getExchange()).isEqualTo(Exchange.BINANCE);
    }

    /**
     * Batch 6a: reference_exchange 显式设非默认值(OKX),验证 insert + 5 个 SELECT 都映射该列。
     * 覆盖 findById / findActiveByAccount / findExpiredGtd / findByQuery / findByExchangeOrderId。
     */
    @Test
    void exchange_roundTripsAcrossAllSelects() {
        long acct = uniqueAccountId();
        Order o = baseLimitBuyOrder(acct, "42000.00", TimeInForce.GTC, null);
        o.setExchange(Exchange.OKX);
        orderMapper.insert(o);

        // findById
        assertThat(orderMapper.findById(o.getId()).getExchange()).isEqualTo(Exchange.OKX);

        // findActiveByAccount(NEW 不在 NOT IN 终态列表,活跃)
        assertThat(orderMapper.findActiveByAccount(acct))
                .singleElement()
                .extracting(Order::getExchange)
                .isEqualTo(Exchange.OKX);

        // findByQuery
        assertThat(orderMapper.findByQuery(acct, "BTC/USDT", List.of(OrderStatus.NEW), null, null, 100, 0))
                .singleElement()
                .extracting(Order::getExchange)
                .isEqualTo(Exchange.OKX);

        // findByExchangeOrderId(需先 casUpdate 写入 exchange_order_id)
        o.setExchangeOrderId("EXCH-OKX-" + acct);
        o.transitionTo(OrderStatus.PENDING_NEW);
        cas(o);
        assertThat(orderMapper.findByExchangeOrderId(acct, "EXCH-OKX-" + acct).getExchange())
                .isEqualTo(Exchange.OKX);
    }

    @Test
    void findExpiredGtd_returnsExchange() {
        long acct = uniqueAccountId();
        Instant future = Instant.now().plus(2, ChronoUnit.HOURS);

        Order gtdOrder = baseLimitBuyOrder(acct, "42000.00", TimeInForce.GTD, future);
        gtdOrder.setExchange(Exchange.BITGET);
        orderMapper.insert(gtdOrder);
        gtdOrder.transitionTo(OrderStatus.PENDING_NEW);
        cas(gtdOrder);
        gtdOrder.transitionTo(OrderStatus.SUBMITTED);
        cas(gtdOrder);

        List<Order> expired = orderMapper.findExpiredGtd(future.plus(1, ChronoUnit.MINUTES));
        assertThat(expired).extracting(Order::getId).contains(gtdOrder.getId());
        assertThat(expired)
                .filteredOn(o -> o.getId().equals(gtdOrder.getId()))
                .singleElement()
                .extracting(Order::getExchange)
                .isEqualTo(Exchange.BITGET);
    }

    @Test
    void casUpdate_successWhenVersionMatches() {
        long acct = uniqueAccountId();
        Order o = limitBuyOrder(acct, "42000.00", TimeInForce.GTC, null);
        orderMapper.insert(o);

        o.transitionTo(OrderStatus.PENDING_NEW);
        int affected = orderMapper.casUpdate(o);
        assertThat(affected).isEqualTo(1);

        Order reloaded = orderMapper.findById(o.getId());
        assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.PENDING_NEW);
        assertThat(reloaded.getVersion()).isEqualTo(1L);
    }

    @Test
    void casUpdate_failWhenVersionStale() {
        long acct = uniqueAccountId();
        Order o = limitBuyOrder(acct, "42000.00", TimeInForce.GTC, null);
        orderMapper.insert(o);

        // 第一次成功
        o.transitionTo(OrderStatus.PENDING_NEW);
        assertThat(orderMapper.casUpdate(o)).isEqualTo(1);
        // 模拟 stale：仍用 version=0 但实际已是 1
        o.setVersion(0);
        o.setStatus(OrderStatus.SUBMITTED);
        assertThat(orderMapper.casUpdate(o)).isZero();
    }

    @Test
    void findActiveByAccount_excludesTerminal() {
        long acct = uniqueAccountId();
        Order active = limitBuyOrder(acct, "42000.00", TimeInForce.GTC, null);
        orderMapper.insert(active);

        Order terminal = limitBuyOrder(acct, "42000.00", TimeInForce.GTC, null);
        orderMapper.insert(terminal);
        terminal.transitionTo(OrderStatus.PENDING_NEW);
        cas(terminal);
        terminal.transitionTo(OrderStatus.SUBMITTED);
        cas(terminal);
        terminal.transitionTo(OrderStatus.FILLED);
        cas(terminal);

        List<Order> activeList = orderMapper.findActiveByAccount(acct);
        assertThat(activeList).hasSize(1);
        assertThat(activeList.get(0).getId()).isEqualTo(active.getId());
    }

    @Test
    void findExpiredGtd() {
        long acct = uniqueAccountId();
        Instant future = Instant.now().plus(2, ChronoUnit.HOURS);

        Order gtdOrder = limitBuyOrder(acct, "42000.00", TimeInForce.GTD, future);
        orderMapper.insert(gtdOrder);
        gtdOrder.transitionTo(OrderStatus.PENDING_NEW);
        cas(gtdOrder);
        gtdOrder.transitionTo(OrderStatus.SUBMITTED);
        cas(gtdOrder);

        // cutoff 在 future 之后 → 应找到
        List<Order> expired = orderMapper.findExpiredGtd(future.plus(1, ChronoUnit.MINUTES));
        assertThat(expired).extracting(Order::getId).contains(gtdOrder.getId());

        // cutoff 在 future 之前 → 不应找到
        List<Order> noneYet = orderMapper.findExpiredGtd(future.minus(1, ChronoUnit.HOURS));
        assertThat(noneYet).extracting(Order::getId).doesNotContain(gtdOrder.getId());
    }

    @Test
    void findByExchangeOrderId() {
        long acct = uniqueAccountId();
        Order o = limitBuyOrder(acct, "42000.00", TimeInForce.GTC, null);
        orderMapper.insert(o);
        o.transitionTo(OrderStatus.PENDING_NEW);
        o.setExchangeOrderId("EXCH-" + acct);
        cas(o);

        Order found = orderMapper.findByExchangeOrderId(acct, "EXCH-" + acct);
        assertThat(found).isNotNull();
        assertThat(found.getId()).isEqualTo(o.getId());
    }

    @Test
    void findByQuery_filtersByStatusAndTimeRange() {
        long acct = uniqueAccountId();
        Order o = limitBuyOrder(acct, "42000.00", TimeInForce.GTC, null);
        orderMapper.insert(o);

        List<Order> all = orderMapper.findByQuery(acct, "BTC/USDT", List.of(OrderStatus.NEW), null, null, 100, 0);
        assertThat(all).hasSize(1);

        List<Order> none = orderMapper.findByQuery(acct, "BTC/USDT", List.of(OrderStatus.FILLED), null, null, 100, 0);
        assertThat(none).isEmpty();
    }

    /** Task 4b: 批量取消某账户所有未终态订单(重置用,绕状态机)。 */
    @Test
    void cancelAllActiveByAccount_cancelsActiveKeepsTerminal() {
        long acct = uniqueAccountId();
        Order active = limitBuyOrder(acct, "42000.00", TimeInForce.GTC, null);
        orderMapper.insert(active);

        Order terminal = limitBuyOrder(acct, "42000.00", TimeInForce.GTC, null);
        orderMapper.insert(terminal);
        terminal.transitionTo(OrderStatus.PENDING_NEW);
        cas(terminal);
        terminal.transitionTo(OrderStatus.SUBMITTED);
        cas(terminal);
        terminal.transitionTo(OrderStatus.FILLED);
        cas(terminal);

        int affected = orderMapper.cancelAllActiveByAccount(acct);
        assertThat(affected).isEqualTo(1); // 仅 active(NEW)被取消

        Order reloadedActive = orderMapper.findById(active.getId());
        assertThat(reloadedActive.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        Order reloadedTerminal = orderMapper.findById(terminal.getId());
        assertThat(reloadedTerminal.getStatus()).isEqualTo(OrderStatus.FILLED); // 终态不变
    }
}
