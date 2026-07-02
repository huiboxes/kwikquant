package com.kwikquant.strategy.interfaces;

import static org.assertj.core.api.Assertions.*;

import com.kwikquant.shared.infra.ApiResponse;
import com.kwikquant.shared.infra.ErrorCode;
import com.kwikquant.shared.types.LlmProvider;
import com.kwikquant.shared.types.StrategyStatus;
import com.kwikquant.strategy.application.LlmProviderException;
import com.kwikquant.strategy.domain.BacktestTaskNotFoundException;
import com.kwikquant.strategy.domain.BacktestTaskStatus;
import com.kwikquant.strategy.domain.IllegalBacktestTaskStateTransitionException;
import com.kwikquant.strategy.domain.IllegalStrategyCodeStateTransitionException;
import com.kwikquant.strategy.domain.IllegalStrategyStateTransitionException;
import com.kwikquant.strategy.domain.LlmProviderNotSupportedException;
import com.kwikquant.strategy.domain.NoPublishedStrategyCodeException;
import com.kwikquant.strategy.domain.StrategyCodeNotFoundException;
import com.kwikquant.strategy.domain.StrategyCodeStatus;
import com.kwikquant.strategy.domain.StrategyNotFoundException;
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
    void llmProviderNotSupported_maps8002() {
        // 服务端配置错误（adapter 未注入）→ 走 8002 而非 3001 VALIDATION_FAILED
        ApiResponse<Void> r =
                handler.handleLlmProviderNotSupported(new LlmProviderNotSupportedException(LlmProvider.ANTHROPIC));
        assertThat(r.code()).isEqualTo(ErrorCode.LLM_KEY_INVALID_PROVIDER);
        assertThat(r.message()).contains("ANTHROPIC");
    }

    @Test
    void llmProviderException_preStream_maps8003() {
        // Pre-stream provider 异常 → 走 8003 + 通用脱敏文案（不透传 provider raw error）
        ApiResponse<Void> r = handler.handleLlmProviderException(new LlmProviderException(500, "provider oom"));
        assertThat(r.code()).isEqualTo(ErrorCode.LLM_PROVIDER_ERROR);
        // 脱敏：不能透传 provider 原始错误
        assertThat(r.message()).doesNotContain("oom");
    }
}
