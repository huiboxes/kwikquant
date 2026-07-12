package com.kwikquant.trading.domain;

import static org.assertj.core.api.Assertions.*;

import com.kwikquant.shared.types.OrderStatus;
import com.kwikquant.trading.infrastructure.ConcurrencyConflictException;
import org.junit.jupiter.api.Test;

class TradingExceptionsTest {

    @Test
    void illegalStateTransitionCarriesFromTo() {
        IllegalOrderStateTransitionException e =
                new IllegalOrderStateTransitionException(OrderStatus.NEW, OrderStatus.FILLED);
        assertThat(e.from()).isEqualTo(OrderStatus.NEW);
        assertThat(e.to()).isEqualTo(OrderStatus.FILLED);
        assertThat(e.getMessage()).contains("NEW").contains("FILLED");
    }

    @Test
    void orderNotFoundCarriesId() {
        OrderNotFoundException e = new OrderNotFoundException(999L);
        assertThat(e.orderId()).isEqualTo(999L);
        assertThat(e.getMessage()).contains("999");
    }

    @Test
    void insufficientBalanceMessage() {
        InsufficientBalanceException e = new InsufficientBalanceException("not enough USDT");
        assertThat(e.getMessage()).isEqualTo("not enough USDT");
    }

    @Test
    void invalidOrderMessage() {
        InvalidOrderException e = new InvalidOrderException("bad amount");
        assertThat(e.getMessage()).isEqualTo("bad amount");
    }

    @Test
    void matchingExceptionMessage() {
        MatchingException e = new MatchingException("over-fill");
        assertThat(e.getMessage()).isEqualTo("over-fill");
    }

    @Test
    void concurrencyConflictMessage() {
        ConcurrencyConflictException e = new ConcurrencyConflictException("CAS exceeded");
        assertThat(e.getMessage()).isEqualTo("CAS exceeded");
    }
}
