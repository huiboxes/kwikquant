package com.kwikquant.shared.types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class IntervalTest {

    @Test
    void fromCcxt_whenKnownValue_shouldReturnEnum() {
        assertEquals(Interval._1m, Interval.fromCcxt("1m"));
        assertEquals(Interval._1h, Interval.fromCcxt("1h"));
        assertEquals(Interval._1d, Interval.fromCcxt("1d"));
    }

    @Test
    void fromCcxt_whenUnknownValue_shouldThrowIllegalArgumentException() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> Interval.fromCcxt("2m"));
        assertEquals("unsupported interval: 2m", ex.getMessage());
    }

    @Test
    void ccxtValue_shouldReturnTimeString() {
        assertEquals("1m", Interval._1m.ccxtValue());
    }
}
