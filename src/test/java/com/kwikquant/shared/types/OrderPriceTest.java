package com.kwikquant.shared.types;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class OrderPriceTest {

    @Test
    void marketSingleton() {
        assertSame(OrderPrice.Market.INSTANCE, OrderPrice.Market.INSTANCE);
    }

    @Test
    void limitRejectsNonPositive() {
        assertThrows(IllegalArgumentException.class, () -> new OrderPrice.Limit(BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new OrderPrice.Limit(new BigDecimal("-1")));
        assertDoesNotThrow(() -> new OrderPrice.Limit(new BigDecimal("50000")));
    }

    @Test
    void trailingStopValidatesRange() {
        assertThrows(IllegalArgumentException.class, () -> new OrderPrice.TrailingStop(BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new OrderPrice.TrailingStop(BigDecimal.ONE));
        assertThrows(IllegalArgumentException.class, () -> new OrderPrice.TrailingStop(new BigDecimal("1.5")));
        assertDoesNotThrow(() -> new OrderPrice.TrailingStop(new BigDecimal("0.02")));
    }

    @Test
    void trailingStopWithActivationPrice() {
        var ts = new OrderPrice.TrailingStop(new BigDecimal("0.01"), new BigDecimal("60000"));
        assertEquals(new BigDecimal("60000"), ts.activationPrice());

        var tsNoActivation = new OrderPrice.TrailingStop(new BigDecimal("0.01"));
        assertNull(tsNoActivation.activationPrice());

        assertThrows(
                IllegalArgumentException.class,
                () -> new OrderPrice.TrailingStop(new BigDecimal("0.01"), BigDecimal.ZERO));
        assertThrows(
                IllegalArgumentException.class,
                () -> new OrderPrice.TrailingStop(new BigDecimal("0.01"), new BigDecimal("-1")));
    }

    @Test
    void stopLimitRequiresBothPrices() {
        assertThrows(IllegalArgumentException.class, () -> new OrderPrice.StopLimit(null, new BigDecimal("100")));
        assertDoesNotThrow(() -> new OrderPrice.StopLimit(new BigDecimal("49000"), new BigDecimal("48500")));
    }

    @Test
    void takeProfitLimitRequiresBothPrices() {
        assertThrows(IllegalArgumentException.class, () -> new OrderPrice.TakeProfitLimit(null, new BigDecimal("100")));
        assertThrows(
                IllegalArgumentException.class, () -> new OrderPrice.TakeProfitLimit(new BigDecimal("55000"), null));
        assertDoesNotThrow(() -> new OrderPrice.TakeProfitLimit(new BigDecimal("55000"), new BigDecimal("54800")));
    }

    @Test
    void sealedInterfaceCovers7Variants() {
        OrderPrice[] prices = {
            OrderPrice.Market.INSTANCE,
            new OrderPrice.Limit(new BigDecimal("50000")),
            new OrderPrice.StopMarket(new BigDecimal("49000")),
            new OrderPrice.StopLimit(new BigDecimal("49000"), new BigDecimal("48500")),
            new OrderPrice.TakeProfitMarket(new BigDecimal("55000")),
            new OrderPrice.TakeProfitLimit(new BigDecimal("55000"), new BigDecimal("54800")),
            new OrderPrice.TrailingStop(new BigDecimal("0.02"))
        };
        assertEquals(7, prices.length);
    }
}
