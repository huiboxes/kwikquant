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

/**
 * LLM API 密钥管理 REST 端点。
 *
 * <p>所有端点需经 {@code JwtAuthenticationFilter} 鉴权（SecurityConfig 全局配置），userId 来自 SecurityContext
 * 而非请求体（防越权）。
 */
@RestController
@RequestMapping("/api/v1/ai/keys")
class LlmApiKeyController {

    private final LlmApiKeyService keyService;

    LlmApiKeyController(LlmApiKeyService keyService) {
        this.keyService = keyService;
    }

    @PostMapping
    public ApiResponse<LlmApiKeyView> create(@Valid @RequestBody CreateLlmKeyRequest req) {
        long userId = SecurityUtils.currentUserId();
        var entity = keyService.create(userId, req.label(), req.provider(), req.apiKey(), req.baseUrl());
        return ApiResponse.ok(keyService.view(entity));
    }

    @GetMapping
    public ApiResponse<List<LlmApiKeyView>> list() {
        return ApiResponse.ok(keyService.listByUser(SecurityUtils.currentUserId()));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable long id) {
        keyService.delete(id, SecurityUtils.currentUserId());
        return ApiResponse.ok(null);
    }

    record CreateLlmKeyRequest(
            // label 会作为 audit 记录的 targetId 写入审计日志（{@code @Auditable(targetId="#label")}），
            // 白名单只允许字母/数字/空格/下划线/中划线，拒绝 "sk-" / "@" / "." 等常出现在 secret 或 email 中的字符，
            // 防止用户误把 API key 前缀/账号名当 label 而被审计日志固化。
            @NotBlank @Size(min = 1, max = 100) @Pattern(regexp = "^[A-Za-z0-9 _-]{1,100}$") String label,
            @NotNull LlmProvider provider,
            @NotBlank @Size(max = 500) String apiKey,
            @Size(max = 500) String baseUrl) {}
}
