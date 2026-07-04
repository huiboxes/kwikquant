package com.kwikquant.account.interfaces;

import com.kwikquant.shared.infra.ApiResponse;
import com.kwikquant.shared.infra.McpTokenService;
import com.kwikquant.shared.infra.SecurityUtils;
import com.kwikquant.shared.types.McpTokenIssueResult;
import com.kwikquant.shared.types.McpTokenView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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
 * MCP PAT 管理 REST 端点。PAT 是用户凭证，归账户域（§3.1 模块定位），直调 shared {@link McpTokenService}，
 * 无 facade 中间层。
 *
 * <p>所有端点需经 {@code JwtAuthenticationFilter} 鉴权（SecurityConfig 全局配置），userId 来自
 * SecurityContext 而非请求体（防越权）。同名重复抛 {@link com.kwikquant.shared.infra.DuplicateMcpTokenException}，
 * GlobalExceptionHandler 映射 3001 VALIDATION_FAILED。
 */
@RestController
@RequestMapping("/api/v1/mcp/tokens")
@Tag(name = "MCP 令牌")
class McpTokenController {

    private final McpTokenService tokenService;

    McpTokenController(McpTokenService tokenService) {
        this.tokenService = tokenService;
    }

    @PostMapping
    @Operation(
            summary = "创建 MCP PAT",
            description = "需 JWT 鉴权。创建 Personal Access Token，**明文 token 仅在此响应中返回一次，后续列表不再返回，请即保存**。"
                    + "同名 token 重复返回 400（3001）。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "400",
            description = "token 名重复或格式非法（3001 VALIDATION_FAILED）")
    public ApiResponse<McpTokenIssueResult> issue(@Valid @RequestBody CreateMcpTokenRequest req) {
        long userId = SecurityUtils.currentUserId();
        McpTokenIssueResult result = tokenService.issue(userId, req.name());
        return ApiResponse.ok(result);
    }

    @GetMapping
    @Operation(summary = "查询当前用户 PAT 列表", description = "需 JWT 鉴权。仅返回 token 元信息（name/创建时间/状态），不含明文 token。")
    public ApiResponse<List<McpTokenView>> list() {
        return ApiResponse.ok(tokenService.listByUser(SecurityUtils.currentUserId()));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "吊销 MCP PAT", description = "需 JWT 鉴权。仅可吊销本人 token；越权返回 403（1002），token 不存在返回 404（4001）。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "403",
            description = "越权吊销他人 token（1002 FORBIDDEN）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "token 不存在（4001 RESOURCE_NOT_FOUND）")
    public ApiResponse<Void> revoke(@Parameter(description = "token ID", example = "42") @PathVariable long id) {
        tokenService.revoke(id, SecurityUtils.currentUserId());
        return ApiResponse.ok(null);
    }

    record CreateMcpTokenRequest(
            // name 白名单：字母/数字/空格/下划线/中划线，1-64 字符（与 V18 VARCHAR(64) 对齐）。
            @Schema(
                            description = "token 名称，1-64 字符，仅字母/数字/空格/_/-",
                            example = "ci-bot-token",
                            requiredMode = Schema.RequiredMode.REQUIRED)
                    @NotBlank
                    @Size(min = 1, max = 64)
                    @Pattern(regexp = "^[A-Za-z0-9 _-]{1,64}$")
                    String name) {}
}
