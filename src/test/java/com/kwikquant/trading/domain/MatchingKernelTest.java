package com.kwikquant.trading.domain;

import static org.assertj.core.api.Assertions.*;

import com.kwikquant.shared.types.OrderSide;
import com.kwikquant.shared.types.OrderStatus;
import com.kwikquant.shared.types.OrderType;
import com.kwikquant.shared.types.PriceLevel;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MatchingKernelTest {

    private static Order limitOrder(OrderSide side, String price, String amount) {
        Order o = new Order();
        o.setId(1L);
        o.setAccountId(1L);
        o.setSymbol("BTC/USDT");
        o.setSide(side);
        o.setOrderType(OrderType.LIMIT);
        o.setAmount(new BigDecimal(amount));
        o.setPrice(new BigDecimal(price));
        o.setStatus(OrderStatus.SUBMITTED);
        o.setFilledQty(BigDecimal.ZERO);
        o.setTimeInForce(TimeInForce.GTC);
        return o;
    }

    private static Order marketOrder(OrderSide side, String amount) {
        Order o = new Order();
        o.setId(2L);
        o.setAccountId(1L);
        o.setSymbol("BTC/USDT");
        o.setSide(side);
        o.setOrderType(OrderType.MARKET);
        o.setAmount(new BigDecimal(amount));
        o.setStatus(OrderStatus.PENDING_NEW);
        o.setFilledQty(BigDecimal.ZERO);
        o.setTimeInForce(TimeInForce.IOC);
        return o;
    }

    private static MarketSnapshot klineSnap(String open, String high, String low, String close) {
        return new MarketSnapshot(
                Instant.parse("2026-06-30T00:00:00Z"),
                new BigDecimal(close),
                null,
                null,
                new BigDecimal(open),
                new BigDecimal(high),
                new BigDecimal(low),
                new BigDecimal(close),
                BigDecimal.TEN,
                List.of(),
                List.of());
    }

    private static MarketSnapshot tickerSnap(String last, String bid, String ask) {
        return new MarketSnapshot(
                Instant.parse("2026-06-30T00:00:00Z"),
                new BigDecimal(last),
                new BigDecimal(bid),
                new BigDecimal(ask),
                new BigDecimal(last),
                new BigDecimal(last),
                new BigDecimal(last),
                new BigDecimal(last),
                BigDecimal.ONE,
                List.of(),
                List.of());
    }

    private static MarketSnapshot orderBookSnap(String last, List<PriceLevel> bids, List<PriceLevel> asks) {
        return new MarketSnapshot(
                Instant.parse("2026-06-30T00:00:00Z"),
                new BigDecimal(last),
                bids.isEmpty() ? null : bids.get(0).price(),
                asks.isEmpty() ? null : asks.get(0).price(),
                new BigDecimal(last),
                new BigDecimal(last),
                new BigDecimal(last),
                new BigDecimal(last),
                BigDecimal.ONE,
                bids,
                asks);
    }

    // ---------- FAST limit ----------

    @Test
    void fastLimit_buyCrossesLow_fills() {
        Order o = limitOrder(OrderSide.BUY, "42000", "0.1");
        MarketSnapshot snap = klineSnap("42050", "42100", "41980", "42030");
        Optional<Fill> fill = MatchingKernel.match(o, snap, MatchConfig.defaults());
        assertThat(fill).isPresent();
        assertThat(fill.get().getPrice()).isEqualByComparingTo("42000");
        assertThat(fill.get().getQty()).isEqualByComparingTo("0.1");
        assertThat(fill.get().getLiquidity()).isEqualTo("maker");
    }

    @Test
    void fastLimit_buyNotCrossed_noFill() {
        Order o = limitOrder(OrderSide.BUY, "42000", "0.1");
        MarketSnapshot snap = klineSnap("42050", "42100", "42030", "42080");
        assertThat(MatchingKernel.match(o, snap, MatchConfig.defaults())).isEmpty();
    }

    @Test
    void fastLimit_sellCrossesHigh_fills() {
        Order o = limitOrder(OrderSide.SELL, "42100", "0.1");
        MarketSnapshot snap = klineSnap("42050", "42150", "41980", "42030");
        Optional<Fill> fill = MatchingKernel.match(o, snap, MatchConfig.defaults());
        assertThat(fill).isPresent();
        assertThat(fill.get().getPrice()).isEqualByComparingTo("42100");
        assertThat(fill.get().getLiquidity()).isEqualTo("maker");
    }

    @Test
    void fastLimit_sellNotCrossed_noFill() {
        Order o = limitOrder(OrderSide.SELL, "42200", "0.1");
        MarketSnapshot snap = klineSnap("42050", "42100", "42030", "42080");
        assertThat(MatchingKernel.match(o, snap, MatchConfig.defaults())).isEmpty();
    }

    // ---------- FAST market ----------

    @Test
    void fastMarket_buyAppliesPositiveSlippage() {
        Order o = marketOrder(OrderSide.BUY, "0.1");
        MarketSnapshot snap = klineSnap("42000", "42000", "42000", "42000");
        Optional<Fill> fill = MatchingKernel.match(o, snap, MatchConfig.defaults());
        assertThat(fill).isPresent();
        // last=42000, slippage=5bps=0.0005, buy → fillPrice = 42000 * 1.0005 = 42021
        assertThat(fill.get().getPrice()).isEqualByComparingTo("42021.00000000");
        assertThat(fill.get().getLiquidity()).isEqualTo("taker");
    }

    @Test
    void fastMarket_sellAppliesNegativeSlippage() {
        Order o = marketOrder(OrderSide.SELL, "0.1");
        MarketSnapshot snap = klineSnap("42000", "42000", "42000", "42000");
        Optional<Fill> fill = MatchingKernel.match(o, snap, MatchConfig.defaults());
        assertThat(fill).isPresent();
        // last=42000, slippage=5bps=0.0005, sell → fillPrice = 42000 * 0.9995 = 41979
        assertThat(fill.get().getPrice()).isEqualByComparingTo("41979.00000000");
    }

    // ---------- SPREAD ----------

    @Test
    void spreadLimit_buyCrossesLast() {
        Order o = limitOrder(OrderSide.BUY, "42000", "0.1");
        MarketSnapshot snap = tickerSnap("41995", "41990", "42000");
        assertThat(MatchingKernel.match(o, snap, MatchConfig.spread())).isPresent();
    }

    @Test
    void spreadLimit_buyNotCrossed_noFill() {
        Order o = limitOrder(OrderSide.BUY, "42000", "0.1");
        MarketSnapshot snap = tickerSnap("42010", "42005", "42015");
        assertThat(MatchingKernel.match(o, snap, MatchConfig.spread())).isEmpty();
    }

    @Test
    void spreadMarket_buyUsesAsk() {
        Order o = marketOrder(OrderSide.BUY, "0.1");
        MarketSnapshot snap = tickerSnap("42000", "41990", "42010");
        Optional<Fill> fill = MatchingKernel.match(o, snap, MatchConfig.spread());
        assertThat(fill).isPresent();
        assertThat(fill.get().getPrice()).isEqualByComparingTo("42010");
    }

    @Test
    void spreadMarket_sellUsesBid() {
        Order o = marketOrder(OrderSide.SELL, "0.1");
        MarketSnapshot snap = tickerSnap("42000", "41990", "42010");
        Optional<Fill> fill = MatchingKernel.match(o, snap, MatchConfig.spread());
        assertThat(fill).isPresent();
        assertThat(fill.get().getPrice()).isEqualByComparingTo("41990");
    }

    // ---------- DEPTH ----------

    @Test
    void depthMarket_buyWalksAsks() {
        Order o = marketOrder(OrderSide.BUY, "1.5");
        List<PriceLevel> asks = List.of(
                new PriceLevel(new BigDecimal("42000"), new BigDecimal("1.0")),
                new PriceLevel(new BigDecimal("42010"), new BigDecimal("1.0")));
        MarketSnapshot snap = orderBookSnap("42000", List.of(), asks);
        Optional<Fill> fill = MatchingKernel.match(o, snap, MatchConfig.depth());
        assertThat(fill).isPresent();
        // qty=1.5: 1.0 @ 42000 + 0.5 @ 42010 = 63005 / 1.5 = 42003.33333333
        assertThat(fill.get().getPrice()).isEqualByComparingTo("42003.33333333");
    }

    @Test
    void depthMarket_sellWalksBids() {
        Order o = marketOrder(OrderSide.SELL, "0.5");
        List<PriceLevel> bids = List.of(new PriceLevel(new BigDecimal("41990"), new BigDecimal("1.0")));
        MarketSnapshot snap = orderBookSnap("42000", bids, List.of());
        Optional<Fill> fill = MatchingKernel.match(o, snap, MatchConfig.depth());
        assertThat(fill).isPresent();
        assertThat(fill.get().getPrice()).isEqualByComparingTo("41990");
    }

    @Test
    void depthMarket_emptyBookReturnsEmpty() {
        Order o = marketOrder(OrderSide.BUY, "0.1");
        MarketSnapshot snap = orderBookSnap("42000", List.of(), List.of());
        assertThat(MatchingKernel.match(o, snap, MatchConfig.depth())).isEmpty();
    }

    @Test
    void depthMarket_insufficientLiquidityFillsAtLastLevelPrice() {
        Order o = marketOrder(OrderSide.BUY, "2.0");
        List<PriceLevel> asks = List.of(new PriceLevel(new BigDecimal("42000"), new BigDecimal("1.0")));
        MarketSnapshot snap = orderBookSnap("42000", List.of(), asks);
        Optional<Fill> fill = MatchingKernel.match(o, snap, MatchConfig.depth());
        assertThat(fill).isPresent();
        // 1.0 真实 + 1.0 估算 @ 42000 = 84000 / 2.0 = 42000
        assertThat(fill.get().getPrice()).isEqualByComparingTo("42000");
    }

    // ---------- terminal / edge ----------

    @Test
    void terminalOrderProducesNoFill() {
        Order o = limitOrder(OrderSide.BUY, "42000", "0.1");
        o.setStatus(OrderStatus.FILLED);
        MarketSnapshot snap = klineSnap("42000", "42010", "41990", "42000");
        assertThat(MatchingKernel.match(o, snap, MatchConfig.defaults())).isEmpty();
    }

    @Test
    void alreadyFullyFilledProducesNoFill() {
        Order o = limitOrder(OrderSide.BUY, "42000", "0.1");
        o.setFilledQty(new BigDecimal("0.1"));
        MarketSnapshot snap = klineSnap("42000", "42010", "41990", "42000");
        assertThat(MatchingKernel.match(o, snap, MatchConfig.defaults())).isEmpty();
    }

    @Test
    void stopMarketReturnsEmpty_thisWaveDoesNotTrigger() {
        Order o = new Order();
        o.setId(3L);
        o.setAccountId(1L);
        o.setSymbol("BTC/USDT");
        o.setSide(OrderSide.BUY);
        o.setOrderType(OrderType.STOP_MARKET);
        o.setAmount(new BigDecimal("0.1"));
        o.setStopPrice(new BigDecimal("41000"));
        o.setStatus(OrderStatus.SUBMITTED);
        o.setFilledQty(BigDecimal.ZERO);
        MarketSnapshot snap = klineSnap("42000", "42010", "41990", "42000");
        assertThat(MatchingKernel.match(o, snap, MatchConfig.defaults())).isEmpty();
    }

    @Test
    void feeIsApplied() {
        Order o = limitOrder(OrderSide.BUY, "42000", "0.1");
        MarketSnapshot snap = klineSnap("42050", "42100", "41980", "42030");
        Optional<Fill> fill = MatchingKernel.match(o, snap, MatchConfig.defaults());
        assertThat(fill).isPresent();
        // fee = 42000 * 0.1 * 0.001 (maker) = 4.2
        assertThat(fill.get().getFee()).isEqualByComparingTo("4.20000000");
        assertThat(fill.get().getFeeCurrency()).isEqualTo("USDT");
    }
}
