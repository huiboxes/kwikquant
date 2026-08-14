package com.kwikquant.strategy.interfaces;

import com.kwikquant.shared.infra.ApiResponse;
import com.kwikquant.shared.infra.ErrorCode;
import com.kwikquant.shared.infra.MdcKeys;
import com.kwikquant.strategy.domain.BacktestQuotaExceededException;
import com.kwikquant.strategy.domain.BacktestTaskNotFoundException;
import com.kwikquant.strategy.domain.IllegalBacktestTaskStateTransitionException;
import com.kwikquant.strategy.domain.IllegalStrategyCodeStateTransitionException;
import com.kwikquant.strategy.domain.IllegalStrategyStateTransitionException;
import com.kwikquant.strategy.domain.NoPublishedStrategyCodeException;
import com.kwikquant.strategy.domain.StrategyCodeNotFoundException;
import com.kwikquant.strategy.domain.StrategyNotEditableException;
import com.kwikquant.strategy.domain.StrategyNotFoundException;
import com.kwikquant.strategy.domain.WorkerConfigUnavailableException;
import com.kwikquant.strategy.domain.WorkerStartFailedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Strategy 模块异常处理器。优先级高于 {@link com.kwikquant.shared.infra.GlobalExceptionHandler}
 * （@Order(0)），确保 strategy 异常映射到 7xxx/8xxx 段而非兜底 5001。
 *
 * <p><b>not-found 专属码</b>：StrategyNotFoundException→7001、StrategyCodeNotFoundException→7004、
 * BacktestTaskNotFoundException→7100（覆盖父类 ResourceNotFoundException 的通用 4001 映射，@Order(0) 优先）。
 *
 * <p>state-transition 异常映射到 7002/7005/4009 + 409(不落 catch-all 5001)。
 */
@RestControllerAdvice
@Order(0)
public class StrategyExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(StrategyExceptionHandler.class);

    @ExceptionHandler(IllegalStrategyStateTransitionException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiResponse<Void> handleIllegalStrategyTransition(IllegalStrategyStateTransitionException e) {
        return ApiResponse.error(ErrorCode.STRATEGY_ILLEGAL_STATE_TRANSITION, e.getMessage(), traceId());
    }

    @ExceptionHandler(StrategyNotEditableException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiResponse<Void> handleStrategyNotEditable(StrategyNotEditableException e) {
        // update/delete 可编辑性前置检查(非状态机转移,与 7002 区分)
        return ApiResponse.error(ErrorCode.STRATEGY_NOT_EDITABLE, e.getMessage(), traceId());
    }

    @ExceptionHandler(IllegalStrategyCodeStateTransitionException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiResponse<Void> handleIllegalCodeTransition(IllegalStrategyCodeStateTransitionException e) {
        return ApiResponse.error(ErrorCode.STRATEGY_CODE_ILLEGAL_STATE, e.getMessage(), traceId());
    }

    @ExceptionHandler(IllegalBacktestTaskStateTransitionException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiResponse<Void> handleIllegalBacktestTransition(IllegalBacktestTaskStateTransitionException e) {
        // 无专用 backtest-state-transition 码，用通用 RESOURCE_STATE_CONFLICT(4009)
        return ApiResponse.error(ErrorCode.RESOURCE_STATE_CONFLICT, e.getMessage(), traceId());
    }

    @ExceptionHandler(NoPublishedStrategyCodeException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiResponse<Void> handleNoPublishedCode(NoPublishedStrategyCodeException e) {
        return ApiResponse.error(ErrorCode.STRATEGY_NO_PUBLISHED_CODE, e.getMessage(), traceId());
    }

    @ExceptionHandler(BacktestQuotaExceededException.class)
    @ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
    public ApiResponse<Void> handleBacktestQuotaExceeded(BacktestQuotaExceededException e) {
        return ApiResponse.error(ErrorCode.BACKTEST_QUOTA_EXCEEDED, e.getMessage(), traceId());
    }

    @ExceptionHandler(WorkerStartFailedException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> handleWorkerStartFailed(WorkerStartFailedException e) {
        log.error("worker start failed for strategy {}", e.strategyId(), e);
        return ApiResponse.error(ErrorCode.WORKER_START_FAILED, e.getMessage(), traceId());
    }

    // ---- not-found：覆盖父类 ResourceNotFoundException 的通用 4001 映射，给专属码 ----

    @ExceptionHandler(StrategyNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiResponse<Void> handleStrategyNotFound(StrategyNotFoundException e) {
        return ApiResponse.error(ErrorCode.STRATEGY_NOT_FOUND, e.getMessage(), traceId());
    }

    @ExceptionHandler(StrategyCodeNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiResponse<Void> handleStrategyCodeNotFound(StrategyCodeNotFoundException e) {
        return ApiResponse.error(ErrorCode.STRATEGY_CODE_NOT_FOUND, e.getMessage(), traceId());
    }

    @ExceptionHandler(BacktestTaskNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiResponse<Void> handleBacktestTaskNotFound(BacktestTaskNotFoundException e) {
        return ApiResponse.error(ErrorCode.BACKTEST_TASK_NOT_FOUND, e.getMessage(), traceId());
    }

    @ExceptionHandler(WorkerConfigUnavailableException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiResponse<Void> handleWorkerConfigUnavailable(WorkerConfigUnavailableException e) {
        // worker 持有效 RUNNER token 但 config registry 无此 strategyId(已停/重启竞态)→ worker exit
        return ApiResponse.error(ErrorCode.WORKER_CONFIG_UNAVAILABLE, e.getMessage(), traceId());
    }

    private static String traceId() {
        return MDC.get(MdcKeys.TRACE_ID);
    }
}
