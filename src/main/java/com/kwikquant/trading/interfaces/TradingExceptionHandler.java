package com.kwikquant.trading.interfaces;

import com.kwikquant.shared.infra.ApiResponse;
import com.kwikquant.shared.infra.ErrorCode;
import com.kwikquant.trading.domain.BacktestOrderRejectedException;
import com.kwikquant.trading.domain.BacktestTaskNotRunningException;
import com.kwikquant.trading.domain.IllegalOrderStateTransitionException;
import com.kwikquant.trading.domain.InsufficientBalanceException;
import com.kwikquant.trading.domain.InvalidOrderException;
import com.kwikquant.trading.infrastructure.ConcurrencyConflictException;
import com.kwikquant.trading.infrastructure.MatchingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Trading 模块异常处理器。优先级高于 GlobalExceptionHandler（@Order(0)），确保 trading 异常由本类处理。
 */
@RestControllerAdvice
@Order(0)
public class TradingExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(TradingExceptionHandler.class);

    @ExceptionHandler(IllegalOrderStateTransitionException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public ApiResponse<Void> handleIllegalTransition(IllegalOrderStateTransitionException e) {
        return ApiResponse.error(ErrorCode.ORDER_ILLEGAL_STATE_TRANSITION, e.getMessage(), traceId());
    }

    @ExceptionHandler(InvalidOrderException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleInvalidOrder(InvalidOrderException e) {
        return ApiResponse.error(ErrorCode.ORDER_INVALID_PARAMS, e.getMessage(), traceId());
    }

    @ExceptionHandler(InsufficientBalanceException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public ApiResponse<Void> handleInsufficientBalance(InsufficientBalanceException e) {
        return ApiResponse.error(ErrorCode.ORDER_INSUFFICIENT_BALANCE, e.getMessage(), traceId());
    }

    @ExceptionHandler(ConcurrencyConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiResponse<Void> handleConcurrencyConflict(ConcurrencyConflictException e) {
        return ApiResponse.error(ErrorCode.ORDER_CONCURRENCY_CONFLICT, e.getMessage(), traceId());
    }

    @ExceptionHandler(MatchingException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> handleMatchingException(MatchingException e) {
        log.error("matching engine error", e);
        return ApiResponse.error(ErrorCode.ORDER_MATCHING_FAILED, e.getMessage(), traceId());
    }

    @ExceptionHandler(BacktestOrderRejectedException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleBacktestOrderRejected(BacktestOrderRejectedException e) {
        return ApiResponse.error(ErrorCode.BACKTEST_ORDER_REJECTED, e.getMessage(), traceId());
    }

    @ExceptionHandler(BacktestTaskNotRunningException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiResponse<Void> handleBacktestTaskNotRunning(BacktestTaskNotRunningException e) {
        return ApiResponse.error(ErrorCode.BACKTEST_TASK_NOT_RUNNING, e.getMessage(), traceId());
    }

    private static String traceId() {
        return MDC.get("traceId");
    }
}
