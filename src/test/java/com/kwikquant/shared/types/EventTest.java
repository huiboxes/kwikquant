package com.kwikquant.shared.types;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class EventTest {

    @Test
    void tickEventConstruction() {
        var tick = new TickEvent(
                Exchange.BINANCE,
                new Symbol("BTC-USDT"),
                new BigDecimal("50000"),
                null,
                null,
                Instant.now(),
                Instant.now(),
                1L,
                MarketDataQualityStatus.NORMAL);
        assertEquals(Exchange.BINANCE, tick.exchange());
        assertEquals(MarketDataQualityStatus.NORMAL, tick.qualityStatus());
    }

    @Test
    void tickEventWithQualityStatus() {
        var tick = new TickEvent(
                Exchange.BINANCE,
                new Symbol("BTC-USDT"),
                new BigDecimal("50000"),
                null,
                null,
                Instant.now(),
                Instant.now(),
                null,
                MarketDataQualityStatus.NORMAL);
        var stale = tick.withQualityStatus(MarketDataQualityStatus.STALE);
        assertEquals(MarketDataQualityStatus.STALE, stale.qualityStatus());
        assertEquals(tick.exchange(), stale.exchange());
    }

    @Test
    void riskTriggeredEvent() {
        var event =
                new RiskTriggeredEvent(new AccountId(1L), new StrategyId(2L), "max drawdown exceeded", Instant.now());
        assertEquals(1L, event.accountId().value());
        assertTrue(event.reason().contains("drawdown"));
    }

    @Test
    void orderStatusChangedEvent() {
        var event = new OrderStatusChangedEvent(
                new OrderId(1L), new AccountId(2L), OrderStatus.NEW, OrderStatus.PENDING_NEW, Instant.now());
        assertEquals(OrderStatus.NEW, event.previousStatus());
        assertEquals(OrderStatus.PENDING_NEW, event.newStatus());
    }
}
