package com.kwikquant.trading.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.kwikquant.market.domain.Kline;
import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.MarketType;
import com.kwikquant.shared.types.OrderSide;
import com.kwikquant.shared.types.OrderType;
import com.kwikquant.trading.domain.Fill;
import com.kwikquant.trading.domain.MarketSnapshot;
import com.kwikquant.trading.domain.MatchConfig;
import com.kwikquant.trading.domain.MatchingFidelity;
import com.kwikquant.trading.domain.MatchingKernel;
import com.kwikquant.trading.domain.Order;
import com.kwikquant.trading.domain.OrderSubmitCommand;
import com.kwikquant.trading.domain.TimeInForce;
import com.kwikquant.market.domain.TradingPairInfo;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Backtest vs Paper 共享 fixture 一致性测试（CI 强制）。
 *
 * <p>tech-design §7.2: 同一组 (order, MarketSnapshot, FAST config) → BacktestExecutor 和 PaperExecutor 各跑 Kernel →
 * fills 一致（忽略 filledAt/externalFillId）。8 类 fixture 覆盖核心场景。
 *
 * <p>本测试直接调 MatchingKernel.match()，因为两个 Executor 最终都通过同一个纯函数撮合。
 * 一致性保证 = Kernel 是纯函数 + 相同输入 → 相同输出。
 */
class BacktestPaperConsistencyTest {

    private static final MatchConfig FAST_CFG = new MatchConfig(
            MatchingFidelity.FAST,
            new BigDecimal("5"),
            false,
            new BigDecimal("0.001"),
            new BigDecimal("0.002"));

    private static final TradingPairInfo PAIR = new TradingPairInfo(
            Exchange.BINANCE, MarketType.SPOT, "BTC/USDT", "BTC", "USDT",
            new BigDecimal("0.00001"), new BigDecimal("1000"), null, null, true);

    @Test
    @DisplayName("Fixture 1: 限价穿越成交")
    void limitCrossed() {
        Order order = createLimitBuy(new BigDecimal("42000"));
        // Kline low=41980 ≤ 42000 → 触发
        MarketSnapshot snap = klineSnap(new BigDecimal("42100"), new BigDecimal("42200"),
                new BigDecimal("41980"), new BigDecimal("42050"));

        Optional<Fill> fill = MatchingKernel.match(order, snap, FAST_CFG);

        assertThat(fill).isPresent();
        assertThat(fill.get().getPrice()).isEqualByComparingTo("42000");
        assertThat(fill.get().getLiquidity()).isEqualTo("maker");
    }

    @Test
    @DisplayName("Fixture 2: 限价未穿越")
    void limitNotCrossed() {
        Order order = createLimitBuy(new BigDecimal("42000"));
        // Kline low=42050 > 42000 → 不触发
        MarketSnapshot snap = klineSnap(new BigDecimal("42100"), new BigDecimal("42200"),
                new BigDecimal("42050"), new BigDecimal("42150"));

        Optional<Fill> fill = MatchingKernel.match(order, snap, FAST_CFG);

        assertThat(fill).isEmpty();
    }

    @Test
    @DisplayName("Fixture 3: 市价+滑点")
    void marketWithSlippage() {
        Order order = createMarketBuy();
        MarketSnapshot snap = klineSnap(new BigDecimal("42000"), new BigDecimal("42000"),
                new BigDecimal("42000"), new BigDecimal("42000"));

        Optional<Fill> fill = MatchingKernel.match(order, snap, FAST_CFG);

        assertThat(fill).isPresent();
        // FAST buy: last * (1 + slippageBps/10000) = 42000 * 1.0005 = 42021
        assertThat(fill.get().getPrice()).isEqualByComparingTo("42021.00000000");
        assertThat(fill.get().getLiquidity()).isEqualTo("taker");
    }

    @Test
    @DisplayName("Fixture 4: 市价卖出滑点")
    void marketSellWithSlippage() {
        Order order = createMarketSell();
        MarketSnapshot snap = klineSnap(new BigDecimal("42000"), new BigDecimal("42000"),
                new BigDecimal("42000"), new BigDecimal("42000"));

        Optional<Fill> fill = MatchingKernel.match(order, snap, FAST_CFG);

        assertThat(fill).isPresent();
        // FAST sell: last * (1 - slippageBps/10000) = 42000 * 0.9995 = 41979
        assertThat(fill.get().getPrice()).isEqualByComparingTo("41979.00000000");
        assertThat(fill.get().getLiquidity()).isEqualTo("taker");
    }

    @Test
    @DisplayName("Fixture 5: IOC 立即成交")
    void iocFilled() {
        Order order = createIocBuy(new BigDecimal("42000"));
        // low=41980 ≤ 42000 → 触发
        MarketSnapshot snap = klineSnap(new BigDecimal("42100"), new BigDecimal("42200"),
                new BigDecimal("41980"), new BigDecimal("42050"));

        Optional<Fill> fill = MatchingKernel.match(order, snap, FAST_CFG);

        assertThat(fill).isPresent();
        assertThat(fill.get().getPrice()).isEqualByComparingTo("42000");
    }

    @Test
    @DisplayName("Fixture 6: IOC 未成交")
    void iocNotFilled() {
        Order order = createIocBuy(new BigDecimal("42000"));
        // low=42050 > 42000 → 不触发 → IOC 应被上层撤单（Kernel 返回 empty）
        MarketSnapshot snap = klineSnap(new BigDecimal("42100"), new BigDecimal("42200"),
                new BigDecimal("42050"), new BigDecimal("42150"));

        Optional<Fill> fill = MatchingKernel.match(order, snap, FAST_CFG);

        assertThat(fill).isEmpty();
    }

    @Test
    @DisplayName("Fixture 7: GTD 到期前成交")
    void gtdFilledBeforeExpiry() {
        Instant expireAt = Instant.parse("2027-07-01T00:00:00Z");
        Order order = createGtdBuy(new BigDecimal("42000"), expireAt);
        // bar time before expiry, low crosses
        MarketSnapshot snap = klineSnapWithTime(
                Instant.parse("2026-06-30T12:00:00Z"),
                new BigDecimal("42100"), new BigDecimal("42200"),
                new BigDecimal("41980"), new BigDecimal("42050"));

        Optional<Fill> fill = MatchingKernel.match(order, snap, FAST_CFG);

        assertThat(fill).isPresent();
    }

    @Test
    @DisplayName("Fixture 8: 终态订单不撮合")
    void terminalOrderSkipped() {
        Order order = createLimitBuy(new BigDecimal("42000"));
        order.transitionTo(com.kwikquant.shared.types.OrderStatus.PENDING_NEW);
        order.transitionTo(com.kwikquant.shared.types.OrderStatus.FILLED);
        MarketSnapshot snap = klineSnap(new BigDecimal("42100"), new BigDecimal("42200"),
                new BigDecimal("41980"), new BigDecimal("42050"));

        Optional<Fill> fill = MatchingKernel.match(order, snap, FAST_CFG);

        assertThat(fill).isEmpty();
    }

    // --- helpers ---

    private Order createLimitBuy(BigDecimal price) {
        OrderSubmitCommand cmd = new OrderSubmitCommand(
                0L, "BTC/USDT", MarketType.SPOT, OrderSide.BUY, OrderType.LIMIT,
                new BigDecimal("0.1"), price, null, TimeInForce.GTC, null, null);
        return Order.create(cmd, PAIR);
    }

    private Order createMarketBuy() {
        OrderSubmitCommand cmd = new OrderSubmitCommand(
                0L, "BTC/USDT", MarketType.SPOT, OrderSide.BUY, OrderType.MARKET,
                new BigDecimal("0.1"), null, null, TimeInForce.GTC, null, null);
        return Order.create(cmd, PAIR);
    }

    private Order createMarketSell() {
        OrderSubmitCommand cmd = new OrderSubmitCommand(
                0L, "BTC/USDT", MarketType.SPOT, OrderSide.SELL, OrderType.MARKET,
                new BigDecimal("0.1"), null, null, TimeInForce.GTC, null, null);
        return Order.create(cmd, PAIR);
    }

    private Order createIocBuy(BigDecimal price) {
        OrderSubmitCommand cmd = new OrderSubmitCommand(
                0L, "BTC/USDT", MarketType.SPOT, OrderSide.BUY, OrderType.LIMIT,
                new BigDecimal("0.1"), price, null, TimeInForce.IOC, null, null);
        return Order.create(cmd, PAIR);
    }

    private Order createGtdBuy(BigDecimal price, Instant expireAt) {
        OrderSubmitCommand cmd = new OrderSubmitCommand(
                0L, "BTC/USDT", MarketType.SPOT, OrderSide.BUY, OrderType.LIMIT,
                new BigDecimal("0.1"), price, null, TimeInForce.GTD, expireAt, null);
        return Order.create(cmd, PAIR);
    }

    private MarketSnapshot klineSnap(BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close) {
        return klineSnapWithTime(Instant.now(), open, high, low, close);
    }

    private MarketSnapshot klineSnapWithTime(Instant time, BigDecimal open, BigDecimal high,
                                              BigDecimal low, BigDecimal close) {
        // MarketSnapshot.fromKline 使用 Kline record
        Kline k = new Kline(Exchange.BINANCE, MarketType.SPOT, "BTC/USDT",
                com.kwikquant.shared.types.Interval._1h, time, open, high, low, close, BigDecimal.ZERO);
        return MarketSnapshot.fromKline(k);
    }
}
