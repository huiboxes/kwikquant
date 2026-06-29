package com.kwikquant.shared.types;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class EnumTest {

    @Test
    void orderSideValues() {
        assertEquals(2, OrderSide.values().length);
        assertNotNull(OrderSide.valueOf("BUY"));
        assertNotNull(OrderSide.valueOf("SELL"));
    }

    @Test
    void orderTypeValues() {
        assertEquals(7, OrderType.values().length);
        assertNotNull(OrderType.valueOf("MARKET"));
        assertNotNull(OrderType.valueOf("LIMIT"));
        assertNotNull(OrderType.valueOf("STOP_MARKET"));
        assertNotNull(OrderType.valueOf("STOP_LIMIT"));
        assertNotNull(OrderType.valueOf("TAKE_PROFIT_MARKET"));
        assertNotNull(OrderType.valueOf("TAKE_PROFIT_LIMIT"));
        assertNotNull(OrderType.valueOf("TRAILING_STOP"));
    }

    @Test
    void exchange_shouldContainOkx() {
        assertNotNull(Exchange.valueOf("OKX"));
    }

    @Test
    void marketType_shouldContainSpotAndPerp() {
        assertEquals(2, MarketType.values().length);
        assertNotNull(MarketType.valueOf("SPOT"));
        assertNotNull(MarketType.valueOf("PERP"));
    }
}
