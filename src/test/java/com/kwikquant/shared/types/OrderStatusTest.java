package com.kwikquant.shared.types;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class OrderStatusTest {

    @Test
    void newCanTransitionToPendingNew() {
        assertTrue(OrderStatus.NEW.canTransitionTo(OrderStatus.PENDING_NEW));
    }

    @Test
    void newCanTransitionToInternalRejected() {
        assertTrue(OrderStatus.NEW.canTransitionTo(OrderStatus.INTERNAL_REJECTED));
    }

    @Test
    void newCannotTransitionToFilled() {
        assertFalse(OrderStatus.NEW.canTransitionTo(OrderStatus.FILLED));
    }

    @Test
    void pendingNewTransitionsToSubmittedOrRejected() {
        assertTrue(OrderStatus.PENDING_NEW.canTransitionTo(OrderStatus.SUBMITTED));
        assertTrue(OrderStatus.PENDING_NEW.canTransitionTo(OrderStatus.INTERNAL_REJECTED));
        assertFalse(OrderStatus.PENDING_NEW.canTransitionTo(OrderStatus.FILLED));
    }

    @ParameterizedTest
    @EnumSource(
            value = OrderStatus.class,
            names = {"FILLED", "CANCELED", "EXPIRED", "EXCHANGE_REJECTED", "INTERNAL_REJECTED"})
    void terminalStatesHaveNoTransitions(OrderStatus status) {
        assertTrue(status.isTerminal());
        assertTrue(status.allowedTransitions().isEmpty());
    }

    @Test
    void cancelRequestedCanRestoreToExchangeAccepted() {
        assertTrue(OrderStatus.CANCEL_REQUESTED.canTransitionTo(OrderStatus.EXCHANGE_ACCEPTED));
    }

    @Test
    void partiallyFilledAllowsSelfTransition() {
        assertTrue(OrderStatus.PARTIALLY_FILLED.canTransitionTo(OrderStatus.PARTIALLY_FILLED));
    }

    @Test
    void partiallyFilledCanExpire() {
        assertTrue(OrderStatus.PARTIALLY_FILLED.canTransitionTo(OrderStatus.EXPIRED));
    }

    @Test
    void partiallyFilledFullTransitions() {
        var allowed = OrderStatus.PARTIALLY_FILLED.allowedTransitions();
        assertTrue(allowed.contains(OrderStatus.PARTIALLY_FILLED));
        assertTrue(allowed.contains(OrderStatus.FILLED));
        assertTrue(allowed.contains(OrderStatus.CANCEL_REQUESTED));
        assertTrue(allowed.contains(OrderStatus.EXPIRED));
        assertEquals(4, allowed.size());
    }
}
