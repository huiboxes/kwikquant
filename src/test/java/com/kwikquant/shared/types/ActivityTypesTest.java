package com.kwikquant.shared.types;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ActivityTypesTest {

    @Test
    void all_containsExpectedTypes() {
        assertEquals(3, ActivityTypes.ALL.size());
        assertTrue(ActivityTypes.ALL.contains(ActivityTypes.ORDER_FILLED));
        assertTrue(ActivityTypes.ALL.contains(ActivityTypes.RISK_TRIGGERED));
        assertTrue(ActivityTypes.ALL.contains(ActivityTypes.STRATEGY_STATE_CHANGED));
    }

    @Test
    void constants_matchStringValues() {
        assertEquals("ORDER_FILLED", ActivityTypes.ORDER_FILLED);
        assertEquals("RISK_TRIGGERED", ActivityTypes.RISK_TRIGGERED);
        assertEquals("STRATEGY_STATE_CHANGED", ActivityTypes.STRATEGY_STATE_CHANGED);
    }
}
