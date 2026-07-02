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
class AiChatController {

    private final AiChatService aiChatService;

    AiChatController(AiChatService aiChatService) {
        this.aiChatService = aiChatService;
    }

    @PostMapping
    public Flux<ServerSentEvent<String>> chat(@Valid @RequestBody AiChatRequest request) {
        return aiChatService.chat(request, SecurityUtils.currentUserId());
    }
}
