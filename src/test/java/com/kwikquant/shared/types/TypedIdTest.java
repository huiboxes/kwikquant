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
    void signalIdDeterministicGeneration() {
        SignalId s1 = SignalId.deterministic("manual-signal:1:key-abc");
        SignalId s2 = SignalId.deterministic("manual-signal:1:key-abc");
        assertEquals(s1, s2);

        SignalId s3 = SignalId.deterministic("manual-signal:1:key-xyz");
        assertNotEquals(s1, s3);
    }

    @Test
    void correlationIdRandom() {
        CorrelationId c1 = CorrelationId.random();
        CorrelationId c2 = CorrelationId.random();
        assertNotEquals(c1, c2);
    }

    @Test
    void symbolRejectsBlank() {
        assertThrows(IllegalArgumentException.class, () -> new Symbol(""));
        assertThrows(IllegalArgumentException.class, () -> new Symbol("  "));
        assertEquals("BTC-USDT", new Symbol("BTC-USDT").value());
    }

    @Test
    void currencyCodeRejectsLowercaseAndTooShort() {
        assertThrows(IllegalArgumentException.class, () -> new CurrencyCode("usd"));
        assertThrows(IllegalArgumentException.class, () -> new CurrencyCode("US"));
        assertDoesNotThrow(() -> new CurrencyCode("USDT"));
        assertDoesNotThrow(() -> new CurrencyCode("BTC"));
    }

    @Test
    void exchangeOrderIdRejectsTooLong() {
        assertThrows(IllegalArgumentException.class, () -> new ExchangeOrderId("X".repeat(129)));
        assertDoesNotThrow(() -> new ExchangeOrderId("X".repeat(128)));
        assertDoesNotThrow(() -> new ExchangeOrderId("abc123"));
    }
}
