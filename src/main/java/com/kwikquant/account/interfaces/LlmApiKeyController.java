package com.kwikquant.account.interfaces;

import com.kwikquant.account.application.LlmApiKeyService;
import com.kwikquant.account.application.LlmApiKeyService.LlmApiKeyView;
import com.kwikquant.shared.infra.ApiResponse;
import com.kwikquant.shared.infra.SecurityUtils;
import com.kwikquant.shared.types.LlmProvider;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * LLM API 密钥管理 REST 端点。
 *
 * <p>所有端点需经 {@code JwtAuthenticationFilter} 鉴权（SecurityConfig 全局配置），userId 来自 SecurityContext
 * 而非请求体（防越权）。
 */
@RestController
@RequestMapping("/api/v1/ai/keys")
@Tag(name = "LLM 密钥")
class LlmApiKeyController {

    private final LlmApiKeyService keyService;

    LlmApiKeyController(LlmApiKeyService keyService) {
        this.keyService = keyService;
    }

    @PostMapping
    @Operation(
            summary = "创建 LLM API 密钥",
            description = "需 JWT 鉴权。完整 key 加密存储（AES-256-GCM），响应仅返回末尾 4 位明文用于识别展示。"
                    + "OPENAI_COMPATIBLE provider 必须传 baseUrl；label 重复返回 400（3001）。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "400",
            description = "参数非法、label 重复或 OPENAI_COMPATIBLE 缺 baseUrl（3001 VALIDATION_FAILED）")
    public ApiResponse<LlmApiKeyView> create(@Valid @RequestBody CreateLlmKeyRequest req) {
        long userId = SecurityUtils.currentUserId();
        var entity = keyService.create(userId, req.label(), req.provider(), req.apiKey(), req.baseUrl());
        return ApiResponse.ok(keyService.view(entity));
    }

    @GetMapping
    @Operation(summary = "查询当前用户 LLM 密钥列表", description = "需 JWT 鉴权。仅返回元信息 + 末尾 4 位明文，不含完整 key。")
    public ApiResponse<List<LlmApiKeyView>> list() {
        return ApiResponse.ok(keyService.listByUser(SecurityUtils.currentUserId()));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除 LLM 密钥", description = "需 JWT 鉴权。仅可删除本人密钥；越权或不存在返回 409（4009）。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "409",
            description = "密钥不存在或不属于当前用户（4009 STATE_CONFLICT）")
    public ApiResponse<Void> delete(@Parameter(description = "密钥 ID", example = "42") @PathVariable long id) {
        keyService.delete(id, SecurityUtils.currentUserId());
        return ApiResponse.ok(null);
    }

    record CreateLlmKeyRequest(
            // label 会作为 audit 记录的 targetId 写入审计日志（{@code @Auditable(targetId="#label")}），
            // 白名单只允许字母/数字/空格/下划线/中划线，拒绝 "sk-" / "@" / "." 等常出现在 secret 或 email 中的字符，
            // 防止用户误把 API key 前缀/账号名当 label 而被审计日志固化。
            @Schema(description = "密钥标签，1-100 字符，仅字母/数字/空格/_/-", example = "主 GPT key", requiredMode = Schema.RequiredMode.REQUIRED)
                    @NotBlank
                    @Size(min = 1, max = 100)
                    @Pattern(regexp = "^[A-Za-z0-9 _-]{1,100}$")
                    String label,
            @Schema(description = "LLM 提供商（枚举: OPENAI | ANTHROPIC | OPENAI_COMPATIBLE 等）", example = "OPENAI", requiredMode = Schema.RequiredMode.REQUIRED)
                    @NotNull
                    LlmProvider provider,
            @Schema(description = "LLM API key（加密存储，响应仅返回末尾 4 位）", example = "sk-proj-abc...xyz", requiredMode = Schema.RequiredMode.REQUIRED)
                    @NotBlank
                    @Size(max = 500)
                    String apiKey,
            @Schema(description = "自定义 base URL，OPENAI_COMPATIBLE 必填，其余可不传", example = "https://api.example.com/v1")
                    @Size(max = 500)
                    String baseUrl) {}
}
