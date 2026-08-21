package com.kwikquant.strategy.interfaces;

import static org.assertj.core.api.Assertions.*;

import com.kwikquant.shared.infra.ApiResponse;
import com.kwikquant.shared.infra.ErrorCode;
import com.kwikquant.shared.types.StrategyStatus;
import com.kwikquant.strategy.domain.BacktestQuotaExceededException;
import com.kwikquant.strategy.domain.BacktestTaskNotFoundException;
import com.kwikquant.strategy.domain.BacktestTaskStatus;
import com.kwikquant.strategy.domain.BacktestWorkerUnavailableException;
import com.kwikquant.strategy.domain.IllegalBacktestTaskStateTransitionException;
import com.kwikquant.strategy.domain.IllegalStrategyCodeStateTransitionException;
import com.kwikquant.strategy.domain.IllegalStrategyStateTransitionException;
import com.kwikquant.strategy.domain.NoPublishedStrategyCodeException;
import com.kwikquant.strategy.domain.StrategyCodeNotFoundException;
import com.kwikquant.strategy.domain.StrategyCodeStatus;
import com.kwikquant.strategy.domain.StrategyNotEditableException;
import com.kwikquant.strategy.domain.StrategyNotFoundException;
import com.kwikquant.strategy.domain.TemplateNotFoundException;
import com.kwikquant.strategy.domain.WorkerStartFailedException;
import org.junit.jupiter.api.Test;

/** 验证 StrategyExceptionHandler 映射到正确错误码（非兜底 5001）。 */
class StrategyExceptionHandlerTest {

    private final StrategyExceptionHandler handler = new StrategyExceptionHandler();

    @Test
    void illegalStrategyTransition_maps7002() {
        ApiResponse<Void> r = handler.handleIllegalStrategyTransition(
                new IllegalStrategyStateTransitionException(StrategyStatus.DRAFT, StrategyStatus.RUNNING));
        assertThat(r.code()).isEqualTo(ErrorCode.STRATEGY_ILLEGAL_STATE_TRANSITION);
        // 断言 message 透传业务上下文，保证 controller 契约（Agent4-M1）
        assertThat(r.message()).contains("DRAFT").contains("RUNNING");
    }

    @Test
    void strategyNotEditable_maps7007() {
        // update/delete 可编辑性前置检查(非状态机转移,与 7002 区分)
        ApiResponse<Void> r =
                handler.handleStrategyNotEditable(new StrategyNotEditableException(StrategyStatus.RUNNING, "删除"));
        assertThat(r.code()).isEqualTo(ErrorCode.STRATEGY_NOT_EDITABLE);
        assertThat(r.message()).contains("RUNNING").contains("删除");
    }

    @Test
    void illegalCodeTransition_maps7005() {
        ApiResponse<Void> r = handler.handleIllegalCodeTransition(new IllegalStrategyCodeStateTransitionException(
                StrategyCodeStatus.PUBLISHED, StrategyCodeStatus.DRAFT));
        assertThat(r.code()).isEqualTo(ErrorCode.STRATEGY_CODE_ILLEGAL_STATE);
    }

    @Test
    void illegalBacktestTransition_maps4009() {
        ApiResponse<Void> r = handler.handleIllegalBacktestTransition(new IllegalBacktestTaskStateTransitionException(
                BacktestTaskStatus.PENDING, BacktestTaskStatus.COMPLETED));
        assertThat(r.code()).isEqualTo(ErrorCode.RESOURCE_STATE_CONFLICT);
    }

    @Test
    void noPublishedCode_maps7006() {
        ApiResponse<Void> r = handler.handleNoPublishedCode(new NoPublishedStrategyCodeException(1L));
        assertThat(r.code()).isEqualTo(ErrorCode.STRATEGY_NO_PUBLISHED_CODE);
    }

    @Test
    void workerStartFailed_maps7200() {
        ApiResponse<Void> r = handler.handleWorkerStartFailed(new WorkerStartFailedException(1L, "docker down", null));
        assertThat(r.code()).isEqualTo(ErrorCode.WORKER_START_FAILED);
    }

    @Test
    void strategyNotFound_maps7001() {
        ApiResponse<Void> r = handler.handleStrategyNotFound(new StrategyNotFoundException(1L));
        assertThat(r.code()).isEqualTo(ErrorCode.STRATEGY_NOT_FOUND);
    }

    @Test
    void strategyCodeNotFound_maps7004() {
        ApiResponse<Void> r = handler.handleStrategyCodeNotFound(new StrategyCodeNotFoundException(5L));
        assertThat(r.code()).isEqualTo(ErrorCode.STRATEGY_CODE_NOT_FOUND);
    }

    @Test
    void backtestTaskNotFound_maps7100() {
        ApiResponse<Void> r = handler.handleBacktestTaskNotFound(new BacktestTaskNotFoundException(9L));
        assertThat(r.code()).isEqualTo(ErrorCode.BACKTEST_TASK_NOT_FOUND);
        assertThat(r.message()).contains("9"); // 透传 backtest task id
    }

    @Test
    void backtestQuotaExceeded_maps7306() {
        ApiResponse<Void> r = handler.handleBacktestQuotaExceeded(new BacktestQuotaExceededException(2, 2));
        assertThat(r.code()).isEqualTo(ErrorCode.BACKTEST_QUOTA_EXCEEDED);
        assertThat(r.message()).contains("2"); // 透传配额数
    }

    @Test
    void templateNotFound_maps7008() {
        ApiResponse<Void> r = handler.handleTemplateNotFound(new TemplateNotFoundException("nope"));
        assertThat(r.code()).isEqualTo(ErrorCode.TEMPLATE_NOT_FOUND);
        assertThat(r.message()).contains("nope");
    }

    @Test
    void workerUnavailable_maps7305() {
        ApiResponse<Void> r =
                handler.handleBacktestWorkerUnavailable(new BacktestWorkerUnavailableException("python 不可执行"));
        assertThat(r.code()).isEqualTo(ErrorCode.BACKTEST_WORKER_UNAVAILABLE);
        assertThat(r.message()).contains("python 不可执行"); // 自检 detail(含修复指引)透传
    }
}
