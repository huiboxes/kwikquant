package com.kwikquant.shared.types;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class ActivityCreatedEventTest {

    @Test
    void constructor_validInput_shouldCreateEvent() {
        var event = new ActivityCreatedEvent(1L, "ORDER_FILLED", "BTC BUY", "PAPER", Instant.now());
        assertEquals(1L, event.userId());
        assertEquals("ORDER_FILLED", event.type());
        assertEquals("BTC BUY", event.title());
        assertEquals("PAPER", event.subtitle());
    }

    @Test
    void constructor_nullType_shouldThrowNPE() {
        assertThrows(
                NullPointerException.class, () -> new ActivityCreatedEvent(1L, null, "title", null, Instant.now()));
    }

    @Test
    void constructor_nullTitle_shouldThrowNPE() {
        assertThrows(NullPointerException.class, () -> new ActivityCreatedEvent(1L, "TYPE", null, null, Instant.now()));
    }

    @Test
    void constructor_titleTooLong_shouldThrowIllegalArgument() {
        String longTitle = "x".repeat(201);
        assertThrows(
                IllegalArgumentException.class,
                () -> new ActivityCreatedEvent(1L, "TYPE", longTitle, null, Instant.now()));
    }

    @Test
    void constructor_subtitleTooLong_shouldThrowIllegalArgument() {
        String longSubtitle = "x".repeat(201);
        assertThrows(
                IllegalArgumentException.class,
                () -> new ActivityCreatedEvent(1L, "TYPE", "ok", longSubtitle, Instant.now()));
    }

    @Test
    void constructor_nullSubtitle_shouldAllow() {
        assertDoesNotThrow(() -> new ActivityCreatedEvent(1L, "TYPE", "title", null, Instant.now()));
    }
}
