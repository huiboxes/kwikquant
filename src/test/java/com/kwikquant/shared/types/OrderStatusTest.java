package com.kwikquant.shared.types;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class OrderStatusTest {

    // ---- NEW transitions ----

    @Test
    void newCanTransitionToPendingNew() {
        assertTrue(OrderStatus.NEW.canTransitionTo(OrderStatus.PENDING_NEW));
    }

    @Test
    void newCanTransitionToRejected() {
        assertTrue(OrderStatus.NEW.canTransitionTo(OrderStatus.REJECTED));
    }

    @Test
    void newCannotTransitionToSubmittedDirectly() {
        assertFalse(OrderStatus.NEW.canTransitionTo(OrderStatus.SUBMITTED));
        assertFalse(OrderStatus.NEW.canTransitionTo(OrderStatus.FILLED));
        assertFalse(OrderStatus.NEW.canTransitionTo(OrderStatus.PARTIALLY_FILLED));
        assertFalse(OrderStatus.NEW.canTransitionTo(OrderStatus.PENDING_CANCEL));
    }

    // ---- PENDING_NEW transitions ----

    @Test
    void pendingNewTransitions() {
        assertTrue(OrderStatus.PENDING_NEW.canTransitionTo(OrderStatus.SUBMITTED));
        assertTrue(OrderStatus.PENDING_NEW.canTransitionTo(OrderStatus.FILLED));
        assertTrue(OrderStatus.PENDING_NEW.canTransitionTo(OrderStatus.PARTIALLY_FILLED));
        assertTrue(OrderStatus.PENDING_NEW.canTransitionTo(OrderStatus.REJECTED));
        assertTrue(OrderStatus.PENDING_NEW.canTransitionTo(OrderStatus.EXPIRED));
        assertFalse(OrderStatus.PENDING_NEW.canTransitionTo(OrderStatus.PENDING_CANCEL));
        assertFalse(OrderStatus.PENDING_NEW.canTransitionTo(OrderStatus.CANCELLED));
    }

    // ---- SUBMITTED transitions ----

    @Test
    void submittedTransitions() {
        assertTrue(OrderStatus.SUBMITTED.canTransitionTo(OrderStatus.PARTIALLY_FILLED));
        assertTrue(OrderStatus.SUBMITTED.canTransitionTo(OrderStatus.FILLED));
        assertTrue(OrderStatus.SUBMITTED.canTransitionTo(OrderStatus.PENDING_CANCEL));
        assertTrue(OrderStatus.SUBMITTED.canTransitionTo(OrderStatus.CANCELLED)); // 交易所快速确认
        assertTrue(OrderStatus.SUBMITTED.canTransitionTo(OrderStatus.REJECTED)); // 交易所异步拒绝
        assertTrue(OrderStatus.SUBMITTED.canTransitionTo(OrderStatus.EXPIRED));
    }

    // ---- PARTIALLY_FILLED transitions ----

    @Test
    void partiallyFilledTransitions() {
        assertTrue(OrderStatus.PARTIALLY_FILLED.canTransitionTo(OrderStatus.FILLED));
        assertTrue(OrderStatus.PARTIALLY_FILLED.canTransitionTo(OrderStatus.PENDING_CANCEL));
        assertTrue(OrderStatus.PARTIALLY_FILLED.canTransitionTo(OrderStatus.CANCELLED)); // 交易所快速确认
        assertTrue(OrderStatus.PARTIALLY_FILLED.canTransitionTo(OrderStatus.REJECTED)); // 交易所异步拒绝
        assertTrue(OrderStatus.PARTIALLY_FILLED.canTransitionTo(OrderStatus.EXPIRED));
        assertFalse(OrderStatus.PARTIALLY_FILLED.canTransitionTo(OrderStatus.PARTIALLY_FILLED));
    }

    // ---- PENDING_CANCEL transitions ----

    @Test
    void pendingCancelTransitions() {
        assertTrue(OrderStatus.PENDING_CANCEL.canTransitionTo(OrderStatus.CANCELLED));
        assertTrue(OrderStatus.PENDING_CANCEL.canTransitionTo(OrderStatus.FILLED));
        assertFalse(OrderStatus.PENDING_CANCEL.canTransitionTo(OrderStatus.SUBMITTED));
        assertFalse(OrderStatus.PENDING_CANCEL.canTransitionTo(OrderStatus.PARTIALLY_FILLED));
    }

    // ---- Terminal states ----

    @ParameterizedTest
    @EnumSource(
            value = OrderStatus.class,
            names = {"FILLED", "CANCELLED", "REJECTED", "EXPIRED"})
    void terminalStatesHaveNoTransitions(OrderStatus status) {
        assertTrue(status.isTerminal());
        assertTrue(status.allowedTransitions().isEmpty());
    }

    @ParameterizedTest
    @EnumSource(
            value = OrderStatus.class,
            names = {"NEW", "PENDING_NEW", "SUBMITTED", "PARTIALLY_FILLED", "PENDING_CANCEL"})
    void nonTerminalStates(OrderStatus status) {
        assertFalse(status.isTerminal());
        assertFalse(status.allowedTransitions().isEmpty());
    }
}
