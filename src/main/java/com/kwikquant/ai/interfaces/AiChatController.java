package com.kwikquant.ai.interfaces;

import com.kwikquant.ai.application.AiChatMessageService;
import com.kwikquant.ai.application.AiChatRequest;
import com.kwikquant.ai.application.AiChatService;
import com.kwikquant.ai.application.ChatMessage;
import com.kwikquant.ai.domain.AiChatMessage;
import com.kwikquant.shared.infra.ApiResponse;
import com.kwikquant.shared.infra.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.List;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * AI Chat 端点(模型选择 + 会话历史持久化)。
 *
 * <p><b>SSE 例外</b>：POST /api/v1/ai/chat 返回 {@link Flux}<{@link ServerSentEvent}>，不套 {@code ApiResponse}（流式响应无法用
 * 单一 envelope 包裹）。pre-stream 阶段异常（key 校验失败等）由 GlobalExceptionHandler 处理；
 * stream 内异常由 {@link AiChatService} 转为 SSE error event（脱敏）。
 *
 * <p><b>会话历史持久化</b>：两个端点管理 per-strategy 会话历史：
 * <ul>
 *   <li>GET /api/v1/strategies/{id}/ai/messages — 按 created_at 升序返 List&lt;AiChatMessageView&gt;(limit 200 防爆)</li>
 *   <li>DELETE /api/v1/strategies/{id}/ai/messages — 清空该策略会话</li>
 * </ul>
 * POST /api/v1/ai/chat 改造:进来时在 controller 层 blocking 保存最后一条 user 消息(role="user", model=null),
 * 再调 {@link AiChatService#chat}(reactive 流内不加 blocking DB 写)。assistant 回复由
 * {@link AiChatService} 在流正常结束时服务端落库(前端不再二次保存——关 tab/断网即丢的窗口消除)。
 *
 * <p><b>路径注解</b>：不使用类级 {@code @RequestMapping} 前缀（端点分属 /api/v1/ai/chat 和 /api/v1/strategies/...
 * 两个不同 base path），每个方法显式声明绝对路径，与 {@code StrategyController} 的 /api/v1/strategies 路径
 * 共存不冲突（Spring MVC 按 method+path 精确匹配）。
 */
@RestController
@Tag(name = "AI 对话")
class AiChatController {

    private final AiChatService aiChatService;
    private final AiChatMessageService messageService;

    AiChatController(AiChatService aiChatService, AiChatMessageService messageService) {
        this.aiChatService = aiChatService;
        this.messageService = messageService;
    }

    @PostMapping("/api/v1/ai/chat")
    @Operation(
            summary = "AI 对话（SSE 流式）",
            description = "需 JWT 鉴权。流式响应，返回 Flux<ServerSentEvent>，不套 ApiResponse envelope。"
                    + "pre-stream 阶段（key 校验等）异常由 GlobalExceptionHandler 处理；LLM provider 不支持返回 500（8002），"
                    + "provider 调用错误返回 502（8003）；stream 内异常转为 SSE error event。"
                    + "需先在 LlmApiKeyController 配置 LLM key。"
                    + "会话历史:传入 strategyId 时,controller 层 blocking 保存最后一条 user 消息(role=user)。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "500",
            description = "LLM provider 未注入/不支持（8002 LLM_KEY_INVALID_PROVIDER）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "502",
            description = "LLM provider 调用错误（8003 LLM_PROVIDER_ERROR）")
    public Flux<ServerSentEvent<String>> chat(@Valid @RequestBody AiChatRequest request) {
        long userId = SecurityUtils.currentUserId();
        // user 消息后端存(controller 层 blocking DB 写,在返 Flux 前)。
        // 不在 AiChatService.chat reactive 流里加 blocking DB 写(reactive 流内 blocking 是反模式)。
        // 只在 strategyId 非空且 messages 非空时存(无 strategyId 的会话不持久化,与设计一致)。
        if (request.strategyId() != null
                && request.messages() != null
                && !request.messages().isEmpty()) {
            ChatMessage last = request.messages().get(request.messages().size() - 1);
            messageService.saveMessage(request.strategyId(), userId, "user", last.content(), null);
        }
        return aiChatService.chat(request, userId);
    }

    @GetMapping("/api/v1/strategies/{strategyId}/ai/messages")
    @Operation(
            summary = "查询策略 AI 会话历史",
            description = "需 JWT 鉴权。按 created_at 升序返回,limit 200 防爆。策略不存在返回 404(7001);非本人策略返回 403。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "策略不存在（7001 STRATEGY_NOT_FOUND）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "非本人策略（1002 FORBIDDEN）")
    public ApiResponse<List<AiChatMessageView>> getMessages(
            @Parameter(description = "策略 ID", example = "128") @PathVariable long strategyId) {
        long userId = SecurityUtils.currentUserId();
        return ApiResponse.ok(messageService.loadHistory(strategyId, userId).stream()
                .map(AiChatController::toView)
                .toList());
    }

    @DeleteMapping("/api/v1/strategies/{strategyId}/ai/messages")
    @Operation(summary = "清空策略 AI 会话历史", description = "需 JWT 鉴权。策略不存在返回 404(7001);非本人策略返回 403。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "策略不存在（7001 STRATEGY_NOT_FOUND）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "非本人策略（1002 FORBIDDEN）")
    public ApiResponse<Void> clearMessages(
            @Parameter(description = "策略 ID", example = "128") @PathVariable long strategyId) {
        long userId = SecurityUtils.currentUserId();
        messageService.deleteAll(strategyId, userId);
        return ApiResponse.ok(null);
    }

    @PostMapping("/api/v1/ai/keys/{id}/test")
    @Operation(
            summary = "测试 LLM Key 连通性",
            description = "需 JWT 鉴权。后端用该 key + model 发最小 ping(messages=[hi], max_tokens=1, 10s 超时),"
                    + "复用 sanitize 脱敏,不透传 provider 原始错误。key 不存在返回 404(4001);非本人返回 403(1002)。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "key 不存在(4001 RESOURCE_NOT_FOUND)")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "403",
            description = "key 不属于当前用户(1002 FORBIDDEN)")
    public ApiResponse<AiChatService.LlmConnectionTestResult> testConnection(
            @Parameter(description = "密钥 ID", example = "42") @PathVariable long id,
            @Parameter(description = "待测模型名", example = "gpt-5.6") @RequestParam @NotBlank String model) {
        long userId = SecurityUtils.currentUserId();
        return ApiResponse.ok(aiChatService.testConnection(id, model, userId));
    }

    /**
     * AI 会话的消息视图(对外契约)。不暴露 user_id(用户只能查自己的,无需暴露)。
     */
    record AiChatMessageView(
            @Schema(description = "消息 ID", example = "42") Long id,
            @Schema(description = "所属策略 ID", example = "128") Long strategyId,
            @Schema(description = "消息角色:user/assistant", example = "user") String role,
            @Schema(description = "消息内容", example = "帮我优化 MA 周期") String content,
            @Schema(description = "AI 消息溯源用的 model(user 消息为 null)", example = "gpt-4o") String model,
            @Schema(description = "创建时间", example = "2026-07-28T12:00:00Z") Instant createdAt) {

        static AiChatMessageView from(AiChatMessage m) {
            return new AiChatMessageView(
                    m.getId(), m.getStrategyId(), m.getRole(), m.getContent(), m.getModel(), m.getCreatedAt());
        }
    }

    private static AiChatMessageView toView(AiChatMessage m) {
        return AiChatMessageView.from(m);
    }
}
