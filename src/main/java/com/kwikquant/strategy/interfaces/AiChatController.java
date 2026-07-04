package com.kwikquant.strategy.interfaces;

import com.kwikquant.shared.infra.SecurityUtils;
import com.kwikquant.strategy.application.AiChatRequest;
import com.kwikquant.strategy.application.AiChatService;
import jakarta.validation.Valid;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import reactor.core.publisher.Flux;

/**
 * AI Chat SSE 端点。
 *
 * <p><b>SSE 例外</b>：返回 {@link Flux}<{@link ServerSentEvent}>，不套 {@code ApiResponse}（流式响应无法用
 * 单一 envelope 包裹）。pre-stream 阶段异常（key 校验失败等）由 GlobalExceptionHandler 处理；
 * stream 内异常由 {@link AiChatService} 转为 SSE error event（脱敏，spec-review S-5）。
 */
@RestController
@RequestMapping("/api/v1/ai/chat")
@Tag(name = "AI 对话")
class AiChatController {

    private final AiChatService aiChatService;

    AiChatController(AiChatService aiChatService) {
        this.aiChatService = aiChatService;
    }

    @PostMapping
    @Operation(
            summary = "AI 对话（SSE 流式）",
            description = "需 JWT 鉴权。流式响应，返回 Flux<ServerSentEvent>，不套 ApiResponse envelope。"
                    + "pre-stream 阶段（key 校验等）异常由 GlobalExceptionHandler 处理；LLM provider 不支持返回 500（8002），"
                    + "provider 调用错误返回 502（8003）；stream 内异常转为 SSE error event。"
                    + "需先在 LlmApiKeyController 配置 LLM key。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "500",
            description = "LLM provider 未注入/不支持（8002 LLM_KEY_INVALID_PROVIDER）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "502",
            description = "LLM provider 调用错误（8003 LLM_PROVIDER_ERROR）")
    public Flux<ServerSentEvent<String>> chat(@Valid @RequestBody AiChatRequest request) {
        return aiChatService.chat(request, SecurityUtils.currentUserId());
    }
}
