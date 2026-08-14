package com.kwikquant.ai.interfaces;

import com.kwikquant.ai.application.LlmProviderException;
import com.kwikquant.ai.domain.LlmProviderNotSupportedException;
import com.kwikquant.shared.infra.ApiResponse;
import com.kwikquant.shared.infra.ErrorCode;
import com.kwikquant.shared.infra.MdcKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * AI 模块异常处理器。LLM provider 异常(pre-stream 阶段)映射到 8xxx 段。
 * AI 对话从 strategy 迁入独立 ai 模块后,异常处理随之归位(原在 StrategyExceptionHandler 内)。
 * stream 内异常由 AiChatService.onErrorResume 脱敏发 SSE error event,此处仅 pre-stream 兜底。
 */
@RestControllerAdvice
@Order(0)
public class AiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(AiExceptionHandler.class);

    /** Provider adapter 未注入。服务端配置问题,非用户输入错误 → 8002。 */
    @ExceptionHandler(LlmProviderNotSupportedException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> handleLlmProviderNotSupported(LlmProviderNotSupportedException e) {
        return ApiResponse.error(ErrorCode.LLM_KEY_INVALID_PROVIDER, e.getMessage(), traceId());
    }

    /**
     * Pre-stream 阶段抛 LlmProviderException 时(stream 内的通过 AiChatService.sanitize 已处理)→ 8003 + 502。
     * 消息不透传给客户端(避免泄露 provider baseUrl/账户片段),仅记录到日志。
     */
    @ExceptionHandler(LlmProviderException.class)
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    public ApiResponse<Void> handleLlmProviderException(LlmProviderException e) {
        log.warn("LLM provider pre-stream error: status={}", e.httpStatus(), e);
        return ApiResponse.error(ErrorCode.LLM_PROVIDER_ERROR, "LLM provider service unavailable", traceId());
    }

    private static String traceId() {
        return MDC.get(MdcKeys.TRACE_ID);
    }
}
