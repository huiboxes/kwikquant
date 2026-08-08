package com.kwikquant.trading.domain;

import static org.assertj.core.api.Assertions.*;

import com.kwikquant.market.domain.TradingPairInfo;
import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.MarginMode;
import com.kwikquant.shared.types.MarketType;
import com.kwikquant.shared.types.OrderSide;
import com.kwikquant.shared.types.OrderStatus;
import com.kwikquant.shared.types.OrderType;
import com.kwikquant.shared.types.PositionEffect;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;

class OrderTest {

    private static TradingPairInfo pairInfo() {
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

    private static OrderSubmitCommand limitBuy(String amount, String price, TimeInForce tif, Instant expireAt) {
        return OrderSubmitCommand.spot(
                1L,
                "BTC/USDT",
                MarketType.SPOT,
                OrderSide.BUY,
                OrderType.LIMIT,
                new BigDecimal(amount),
                price == null ? null : new BigDecimal(price),
                null,
                tif,
                expireAt,
                "client-1");
    }

    // ---------- create ----------

    @Test
    void create_validLimitBuy() {
        Order o = Order.create(limitBuy("0.1", "42000.00", TimeInForce.GTC, null), pairInfo());
        assertThat(o.getStatus()).isEqualTo(OrderStatus.NEW);
        assertThat(o.getVersion()).isZero();
        assertThat(o.getFilledQty()).isEqualByComparingTo("0");
        assertThat(o.getFilledAvgPrice()).isNull();
        assertThat(o.remainingQty()).isEqualByComparingTo("0.1");
        assertThat(o.getTimeInForce()).isEqualTo(TimeInForce.GTC);
    }

    @Test
    void create_defaultsToGTCWhenTifNull() {
        OrderSubmitCommand cmd = OrderSubmitCommand.spot(
                1L,
                "BTC/USDT",
                MarketType.SPOT,
                OrderSide.BUY,
                OrderType.LIMIT,
                new BigDecimal("0.1"),
                new BigDecimal("42000.00"),
                null,
                null,
                null,
                null);
        Order o = Order.create(cmd, pairInfo());
        assertThat(o.getTimeInForce()).isEqualTo(TimeInForce.GTC);
    }

    @Test
    void create_rejectsBlankSymbol() {
        OrderSubmitCommand cmd = OrderSubmitCommand.spot(
                1L,
                "",
                MarketType.SPOT,
                OrderSide.BUY,
                OrderType.LIMIT,
                new BigDecimal("0.1"),
                new BigDecimal("42000"),
                null,
                TimeInForce.GTC,
                null,
                null);
        assertThatThrownBy(() -> Order.create(cmd, pairInfo()))
                .isInstanceOf(InvalidOrderException.class)
                .hasMessageContaining("symbol");
    }

    @Test
    void create_rejectsNegativeAmount() {
        OrderSubmitCommand cmd = limitBuy("-0.1", "42000", TimeInForce.GTC, null);
        assertThatThrownBy(() -> Order.create(cmd, pairInfo()))
                .isInstanceOf(InvalidOrderException.class)
                .hasMessageContaining("amount");
    }

    @Test
    void create_rejectsBelowMinQty() {
        OrderSubmitCommand cmd = limitBuy("0.00001", "42000", TimeInForce.GTC, null);
        assertThatThrownBy(() -> Order.create(cmd, pairInfo()))
                .isInstanceOf(InvalidOrderException.class)
                .hasMessageContaining("minQty");
    }

    @Test
    void create_rejectsLimitWithoutPrice() {
        OrderSubmitCommand cmd = limitBuy("0.1", null, TimeInForce.GTC, null);
        assertThatThrownBy(() -> Order.create(cmd, pairInfo()))
                .isInstanceOf(InvalidOrderException.class)
                .hasMessageContaining("price required");
    }

    @Test
    void create_rejectsPriceMisalignedToTickSize() {
        OrderSubmitCommand cmd = limitBuy("0.1", "42000.123", TimeInForce.GTC, null);
        assertThatThrownBy(() -> Order.create(cmd, pairInfo()))
                .isInstanceOf(InvalidOrderException.class)
                .hasMessageContaining("tickSize");
    }

    @Test
    void create_rejectsGtdWithoutExpireAt() {
        OrderSubmitCommand cmd = limitBuy("0.1", "42000", TimeInForce.GTD, null);
        assertThatThrownBy(() -> Order.create(cmd, pairInfo()))
                .isInstanceOf(InvalidOrderException.class)
                .hasMessageContaining("expireAt required");
    }

    @Test
    void create_rejectsGtdWithExpiredAt() {
        Instant past = Instant.now().minus(1, ChronoUnit.HOURS);
        OrderSubmitCommand cmd = limitBuy("0.1", "42000", TimeInForce.GTD, past);
        assertThatThrownBy(() -> Order.create(cmd, pairInfo()))
                .isInstanceOf(InvalidOrderException.class)
                .hasMessageContaining("future");
    }

    @Test
    void create_acceptsStopLimitWithBothPrices() {
        OrderSubmitCommand cmd = OrderSubmitCommand.spot(
                1L,
                "BTC/USDT",
                MarketType.SPOT,
                OrderSide.BUY,
                OrderType.STOP_LIMIT,
                new BigDecimal("0.1"),
                new BigDecimal("42000"),
                new BigDecimal("41500"),
                TimeInForce.GTC,
                null,
                "c1");
        Order o = Order.create(cmd, pairInfo());
        assertThat(o.getOrderType()).isEqualTo(OrderType.STOP_LIMIT);
        assertThat(o.getStopPrice()).isEqualByComparingTo("41500");
    }

    @Test
    void create_rejectsStopMarketWithoutStopPrice() {
        OrderSubmitCommand cmd = OrderSubmitCommand.spot(
                1L,
                "BTC/USDT",
                MarketType.SPOT,
                OrderSide.BUY,
                OrderType.STOP_MARKET,
                new BigDecimal("0.1"),
                null,
                null,
                TimeInForce.GTC,
                null,
                "c1");
        assertThatThrownBy(() -> Order.create(cmd, pairInfo()))
                .isInstanceOf(InvalidOrderException.class)
                .hasMessageContaining("stopPrice");
    }

    @Test
    void create_rejectsStopPriceMisalignedToTickSize() {
        OrderSubmitCommand cmd = OrderSubmitCommand.spot(
                1L,
                "BTC/USDT",
                MarketType.SPOT,
                OrderSide.SELL,
                OrderType.STOP_MARKET,
                new BigDecimal("0.1"),
                null,
                new BigDecimal("41500.123"),
                TimeInForce.GTC,
                null,
                "c1");
        assertThatThrownBy(() -> Order.create(cmd, pairInfo()))
                .isInstanceOf(InvalidOrderException.class)
                .hasMessageContaining("stopPrice")
                .hasMessageContaining("tickSize");
    }

    @Test
    void create_nullPairInfoFails() {
        OrderSubmitCommand cmd = limitBuy("0.1", "42000", TimeInForce.GTC, null);
        assertThatThrownBy(() -> Order.create(cmd, null))
                .isInstanceOf(InvalidOrderException.class)
                .hasMessageContaining("unknown symbol");
    }

    // ---------- transitionTo ----------

    @Test
    void transitionTo_legal() {
        Order o = Order.create(limitBuy("0.1", "42000", TimeInForce.GTC, null), pairInfo());
        o.transitionTo(OrderStatus.PENDING_NEW);
        assertThat(o.getStatus()).isEqualTo(OrderStatus.PENDING_NEW);
        o.transitionTo(OrderStatus.SUBMITTED);
        assertThat(o.getStatus()).isEqualTo(OrderStatus.SUBMITTED);
    }

    @Test
    void transitionTo_submittedToCancelledAllowed() {
        // 交易所快速确认取消（快于本地 PENDING_CANCEL 推进）
        Order o = Order.create(limitBuy("0.1", "42000", TimeInForce.GTC, null), pairInfo());
        o.transitionTo(OrderStatus.PENDING_NEW);
        o.transitionTo(OrderStatus.SUBMITTED);
        o.transitionTo(OrderStatus.CANCELLED);
        assertThat(o.getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void transitionTo_partiallyFilledToCancelledAllowed() {
        // 部分成交后交易所直接确认取消
        Order o = Order.create(limitBuy("0.1", "42000", TimeInForce.GTC, null), pairInfo());
        o.transitionTo(OrderStatus.PENDING_NEW);
        o.transitionTo(OrderStatus.SUBMITTED);
        o.accumulateFill(new BigDecimal("0.03"), new BigDecimal("42000"));
        o.transitionTo(OrderStatus.PARTIALLY_FILLED);
        o.transitionTo(OrderStatus.CANCELLED);
        assertThat(o.getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void transitionTo_illegalThrows() {
        Order o = Order.create(limitBuy("0.1", "42000", TimeInForce.GTC, null), pairInfo());
        assertThatThrownBy(() -> o.transitionTo(OrderStatus.FILLED))
                .isInstanceOf(IllegalOrderStateTransitionException.class);
    }

    @Test
    void transitionTo_fromTerminalThrows() {
        Order o = Order.create(limitBuy("0.1", "42000", TimeInForce.GTC, null), pairInfo());
        o.transitionTo(OrderStatus.PENDING_NEW);
        o.transitionTo(OrderStatus.SUBMITTED);
        o.transitionTo(OrderStatus.FILLED);
        assertThatThrownBy(() -> o.transitionTo(OrderStatus.CANCELLED))
                .isInstanceOf(IllegalOrderStateTransitionException.class);
    }

    // ---------- accumulateFill ----------

    @Test
    void accumulateFill_firstFillSetsAvgPrice() {
        Order o = Order.create(limitBuy("0.1", "42000", TimeInForce.GTC, null), pairInfo());
        o.accumulateFill(new BigDecimal("0.03"), new BigDecimal("42100"));
        assertThat(o.getFilledQty()).isEqualByComparingTo("0.03");
        assertThat(o.getFilledAvgPrice()).isEqualByComparingTo("42100");
    }

    @Test
    void accumulateFill_secondFillWeightedAverage() {
        Order o = Order.create(limitBuy("0.1", "42000", TimeInForce.GTC, null), pairInfo());
        o.accumulateFill(new BigDecimal("0.03"), new BigDecimal("42100"));
        o.accumulateFill(new BigDecimal("0.02"), new BigDecimal("42150"));
        assertThat(o.getFilledQty()).isEqualByComparingTo("0.05");
        // (0.03*42100 + 0.02*42150) / 0.05 = (1263 + 843) / 0.05 = 42120
        assertThat(o.getFilledAvgPrice()).isEqualByComparingTo("42120");
    }

    @Test
    void accumulateFill_rejectsOverfill() {
        Order o = Order.create(limitBuy("0.1", "42000", TimeInForce.GTC, null), pairInfo());
        o.accumulateFill(new BigDecimal("0.09"), new BigDecimal("42000"));
        assertThatThrownBy(() -> o.accumulateFill(new BigDecimal("0.02"), new BigDecimal("42000")))
                .isInstanceOf(MatchingException.class)
                .hasMessageContaining("over-fill");
    }

    @Test
    void accumulateFill_rejectsNonPositiveQty() {
        Order o = Order.create(limitBuy("0.1", "42000", TimeInForce.GTC, null), pairInfo());
        assertThatThrownBy(() -> o.accumulateFill(BigDecimal.ZERO, new BigDecimal("42000")))
                .isInstanceOf(MatchingException.class)
                .hasMessageContaining("fillQty");
    }

    @Test
    void accumulateFill_rejectsNonPositivePrice() {
        Order o = Order.create(limitBuy("0.1", "42000", TimeInForce.GTC, null), pairInfo());
        assertThatThrownBy(() -> o.accumulateFill(new BigDecimal("0.01"), BigDecimal.ZERO))
                .isInstanceOf(MatchingException.class)
                .hasMessageContaining("fillPrice");
    }

    // ---------- create 合约/精度校验(补 nc 分支)----------

    @Test
    void create_rejectsAmountMisalignedToStepSize() {
        // pairInfo() 的 stepSize=1e-8(几乎任何 amount 都对齐),故用 stepSize=0.01 的 pair 触发:
        // amount=0.015 % 0.01 = 0.005 ≠ 0 → throw
        TradingPairInfo largeStepPair = new TradingPairInfo(
                Exchange.BINANCE,
                MarketType.SPOT,
                "BTC/USDT",
                "BTC",
                "USDT",
                new BigDecimal("0.0001"),
                new BigDecimal("100"),
                new BigDecimal("0.01"),
                new BigDecimal("0.01"),
                true);
        OrderSubmitCommand cmd = OrderSubmitCommand.spot(
                1L,
                "BTC/USDT",
                MarketType.SPOT,
                OrderSide.BUY,
                OrderType.LIMIT,
                new BigDecimal("0.015"),
                new BigDecimal("42000.00"),
                null,
                TimeInForce.GTC,
                null,
                "c1");
        assertThatThrownBy(() -> Order.create(cmd, largeStepPair))
                .isInstanceOf(InvalidOrderException.class)
                .hasMessageContaining("stepSize");
    }

    @Test
    void create_rejectsPerpLeverageOutOfRange() {
        OrderSubmitCommand cmd = OrderSubmitCommand.perp(
                1L,
                "BTC/USDT",
                OrderSide.BUY,
                OrderType.LIMIT,
                new BigDecimal("0.1"),
                new BigDecimal("42000.00"),
                null,
                TimeInForce.GTC,
                null,
                "c1",
                200, // > 125
                MarginMode.ISOLATED,
                PositionEffect.OPEN_LONG);
        assertThatThrownBy(() -> Order.create(cmd, pairInfo()))
                .isInstanceOf(InvalidOrderException.class)
                .hasMessageContaining("leverage must be 1-125");
    }

    /** M4: per-symbol maxLeverage(来自 CCXT market.limits.leverage.max)pre-trade 校验。 */
    @Test
    void create_rejectsPerpLeverageExceedingMaxLeverage() {
        // maxLeverage=20,订单 leverage=50(在 1-125 内但超 per-symbol 上限)→ reject。
        // PAPER 无交易所拒单兜底,缺此校验会撮合 50x 不真实单。
        TradingPairInfo perpPair = new TradingPairInfo(
                Exchange.BINANCE,
                MarketType.PERP,
                "BTC/USDT",
                "BTC",
                "USDT",
                new BigDecimal("0.001"),
                new BigDecimal("100"),
                new BigDecimal("0.1"),
                new BigDecimal("0.001"),
                true,
                20);
        OrderSubmitCommand cmd = OrderSubmitCommand.perp(
                1L,
                "BTC/USDT",
                OrderSide.BUY,
                OrderType.LIMIT,
                new BigDecimal("0.1"),
                new BigDecimal("42000.00"),
                null,
                TimeInForce.GTC,
                null,
                "c1",
                50, // > maxLeverage 20
                MarginMode.ISOLATED,
                PositionEffect.OPEN_LONG);
        assertThatThrownBy(() -> Order.create(cmd, perpPair))
                .isInstanceOf(InvalidOrderException.class)
                .hasMessageContaining("exceeds maxLeverage 20");
    }

    /** M4: leverage == maxLeverage 边界合法(<=)。 */
    @Test
    void create_acceptsPerpLeverageAtMaxLeverage() {
        TradingPairInfo perpPair = new TradingPairInfo(
                Exchange.BINANCE,
                MarketType.PERP,
                "BTC/USDT",
                "BTC",
                "USDT",
                new BigDecimal("0.001"),
                new BigDecimal("100"),
                new BigDecimal("0.1"),
                new BigDecimal("0.001"),
                true,
                20);
        OrderSubmitCommand cmd = OrderSubmitCommand.perp(
                1L,
                "BTC/USDT",
                OrderSide.BUY,
                OrderType.LIMIT,
                new BigDecimal("0.1"),
                new BigDecimal("42000.00"),
                null,
                TimeInForce.GTC,
                null,
                "c1",
                20, // == maxLeverage,边界合法
                MarginMode.ISOLATED,
                PositionEffect.OPEN_LONG);
        Order o = Order.create(cmd, perpPair);
        assertThat(o.getStatus()).isEqualTo(OrderStatus.NEW);
        assertThat(o.getLeverage()).isEqualTo(20);
    }

    @Test
    void create_rejectsPerpWithoutMarginMode() {
        OrderSubmitCommand cmd = OrderSubmitCommand.perp(
                1L,
                "BTC/USDT",
                OrderSide.BUY,
                OrderType.LIMIT,
                new BigDecimal("0.1"),
                new BigDecimal("42000.00"),
                null,
                TimeInForce.GTC,
                null,
                "c1",
                10,
                null, // marginMode missing
                PositionEffect.OPEN_LONG);
        assertThatThrownBy(() -> Order.create(cmd, pairInfo()))
                .isInstanceOf(InvalidOrderException.class)
                .hasMessageContaining("marginMode required");
    }

    @Test
    void create_rejectsPerpWithoutPositionEffect() {
        OrderSubmitCommand cmd = OrderSubmitCommand.perp(
                1L,
                "BTC/USDT",
                OrderSide.BUY,
                OrderType.LIMIT,
                new BigDecimal("0.1"),
                new BigDecimal("42000.00"),
                null,
                TimeInForce.GTC,
                null,
                "c1",
                10,
                MarginMode.ISOLATED,
                null); // positionEffect missing
        assertThatThrownBy(() -> Order.create(cmd, pairInfo()))
                .isInstanceOf(InvalidOrderException.class)
                .hasMessageContaining("positionEffect required");
    }

    @Test
    void create_rejectsSpotWithContractFields() {
        // canonical constructor:marketType=SPOT 但误带 leverage/marginMode/positionEffect → throw
        OrderSubmitCommand cmd = new OrderSubmitCommand(
                1L,
                "BTC/USDT",
                MarketType.SPOT,
                OrderSide.BUY,
                OrderType.LIMIT,
                new BigDecimal("0.1"),
                new BigDecimal("42000.00"),
                null,
                TimeInForce.GTC,
                null,
                "c1",
                10, // SPOT 不应带 leverage
                MarginMode.ISOLATED,
                PositionEffect.OPEN_LONG);
        assertThatThrownBy(() -> Order.create(cmd, pairInfo()))
                .isInstanceOf(InvalidOrderException.class)
                .hasMessageContaining("SPOT order must not set leverage/marginMode/positionEffect");
    }
}
