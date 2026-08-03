package com.kwikquant.shared.types;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class TypedIdTest {

    @Test
    void accountIdRejectsNullAndNegative() {
        assertThrows(IllegalArgumentException.class, () -> new AccountId(null));
        assertThrows(IllegalArgumentException.class, () -> new AccountId(0L));
        assertThrows(IllegalArgumentException.class, () -> new AccountId(-1L));
        assertEquals(1L, new AccountId(1L).value());
    }

    @Test
    void symbolRejectsBlank() {
        assertThrows(IllegalArgumentException.class, () -> new Symbol(""));
        assertThrows(IllegalArgumentException.class, () -> new Symbol("  "));
        assertEquals("BTC-USDT", new Symbol("BTC-USDT").value());
    }
}
