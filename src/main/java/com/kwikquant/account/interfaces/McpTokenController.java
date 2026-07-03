package com.kwikquant.account.interfaces;

import com.kwikquant.shared.infra.ApiResponse;
import com.kwikquant.shared.infra.McpTokenService;
import com.kwikquant.shared.infra.SecurityUtils;
import com.kwikquant.shared.types.McpTokenIssueResult;
import com.kwikquant.shared.types.McpTokenView;
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
class McpTokenController {

    private final McpTokenService tokenService;

    McpTokenController(McpTokenService tokenService) {
        this.tokenService = tokenService;
    }

    @PostMapping
    public ApiResponse<McpTokenIssueResult> issue(@Valid @RequestBody CreateMcpTokenRequest req) {
        long userId = SecurityUtils.currentUserId();
        McpTokenIssueResult result = tokenService.issue(userId, req.name());
        return ApiResponse.ok(result);
    }

    @GetMapping
    public ApiResponse<List<McpTokenView>> list() {
        return ApiResponse.ok(tokenService.listByUser(SecurityUtils.currentUserId()));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> revoke(@PathVariable long id) {
        tokenService.revoke(id, SecurityUtils.currentUserId());
        return ApiResponse.ok(null);
    }

    record CreateMcpTokenRequest(
            // name 白名单：字母/数字/空格/下划线/中划线，1-64 字符（与 V18 VARCHAR(64) 对齐）。
            @NotBlank @Size(min = 1, max = 64) @Pattern(regexp = "^[A-Za-z0-9 _-]{1,64}$") String name) {}
}
