package com.kwikquant.trading.interfaces;

import static org.assertj.core.api.Assertions.*;

import com.kwikquant.shared.infra.ErrorCode;
import com.kwikquant.shared.types.OrderStatus;
import com.kwikquant.trading.domain.IllegalOrderStateTransitionException;
import com.kwikquant.trading.domain.InsufficientBalanceException;
import com.kwikquant.trading.domain.InvalidOrderException;
import com.kwikquant.trading.infrastructure.ConcurrencyConflictException;
import com.kwikquant.trading.infrastructure.MatchingException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TradingExceptionHandlerTest {
    private TradingExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new TradingExceptionHandler();
    }

    @Test
    void handleIllegalTransition_returns422() {
        var ex = new IllegalOrderStateTransitionException(OrderStatus.NEW, OrderStatus.FILLED);
        var response = handler.handleIllegalTransition(ex);
        assertThat(response.code()).isEqualTo(ErrorCode.ORDER_ILLEGAL_STATE_TRANSITION);
    }

    @Test
    void handleInvalidOrder_returns400() {
        var ex = new InvalidOrderException("bad symbol");
        var response = handler.handleInvalidOrder(ex);
        assertThat(response.code()).isEqualTo(ErrorCode.ORDER_INVALID_PARAMS);
    }

    @Test
    void handleInsufficientBalance_returns422() {
        var ex = new InsufficientBalanceException("not enough");
        var response = handler.handleInsufficientBalance(ex);
        assertThat(response.code()).isEqualTo(ErrorCode.ORDER_INSUFFICIENT_BALANCE);
    }

    @Test
    void handleConcurrencyConflict_returns409() {
        var ex = new ConcurrencyConflictException("CAS failed");
        var response = handler.handleConcurrencyConflict(ex);
        assertThat(response.code()).isEqualTo(ErrorCode.ORDER_CONCURRENCY_CONFLICT);
    }

    @Test
    void handleMatchingException_returns500() {
        var ex = new MatchingException("engine error");
        var response = handler.handleMatchingException(ex);
        assertThat(response.code()).isEqualTo(ErrorCode.ORDER_MATCHING_FAILED);
    }
}
