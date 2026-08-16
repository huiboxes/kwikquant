package com.kwikquant.ai.interfaces;

import static org.assertj.core.api.Assertions.*;

import com.kwikquant.ai.application.LlmProviderException;
import com.kwikquant.ai.domain.LlmProviderNotSupportedException;
import com.kwikquant.shared.infra.ApiResponse;
import com.kwikquant.shared.infra.ErrorCode;
import com.kwikquant.shared.types.LlmProvider;
import org.junit.jupiter.api.Test;

/** 验证 AiExceptionHandler 映射到正确错误码（8xxx 段,非兜底 5001）。 */
class AiExceptionHandlerTest {

    private final AiExceptionHandler handler = new AiExceptionHandler();

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
