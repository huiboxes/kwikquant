package com.kwikquant.account.interfaces;

import com.kwikquant.account.application.AuthService;
import com.kwikquant.account.application.AuthService.AuthResult;
import com.kwikquant.shared.infra.ApiResponse;
import com.kwikquant.shared.infra.SecurityUtils;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "认证")
class AuthController {

    private static final String REFRESH_COOKIE = "refresh_token";

    private final AuthService authService;

    AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    @Operation(
            summary = "注册",
            description = "公开端点，不需 JWT。创建用户账号，返回 access token + 设置 refresh token cookie。"
                    + "用户名/邮箱已存在返回 400（3001）。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "400",
            description = "参数非法或用户名/邮箱已存在（3001 VALIDATION_FAILED）")
    public ApiResponse<TokenResponse> register(@Valid @RequestBody RegisterRequest req, HttpServletResponse response) {
        AuthResult result = authService.register(req.username(), req.email(), req.password());
        setRefreshCookie(response, result.refreshToken());
        return ApiResponse.ok(new TokenResponse(result.accessToken(), result.expiresIn()), traceId());
    }

    @PostMapping("/login")
    @Operation(
            summary = "登录",
            description = "公开端点，不需 JWT。校验凭据，返回 access token（有效期 15min）+ 设置 refresh token cookie（有效期 7d）。"
                    + "凭据无效或账户禁用返回 401（1001）。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "401",
            description = "凭据无效或账户禁用（1001 UNAUTHENTICATED）")
    public ApiResponse<TokenResponse> login(@Valid @RequestBody LoginRequest req, HttpServletResponse response) {
        AuthResult result = authService.login(req.username(), req.password());
        setRefreshCookie(response, result.refreshToken());
        return ApiResponse.ok(new TokenResponse(result.accessToken(), result.expiresIn()), traceId());
    }

    @PostMapping("/refresh")
    @Operation(
            summary = "刷新 token",
            description = "公开端点，不需 JWT。用 refresh_token cookie 换新 access token + 新 refresh token（旋转）。"
                    + "refresh token 缺失/失效返回 401（1001）。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "401",
            description = "refresh token 缺失或失效（1001 UNAUTHENTICATED）")
    public ApiResponse<TokenResponse> refresh(
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken, HttpServletResponse response) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new com.kwikquant.account.domain.InvalidCredentialsException();
        }
        AuthResult result = authService.refresh(refreshToken);
        setRefreshCookie(response, result.refreshToken());
        return ApiResponse.ok(new TokenResponse(result.accessToken(), result.expiresIn()), traceId());
    }

    @PostMapping("/logout")
    @Operation(
            summary = "登出",
            description = "需 JWT 鉴权。吊销 refresh token 并清除 cookie。refresh token 缺失也视为登出成功（幂等）。")
    public ApiResponse<Void> logout(
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken, HttpServletResponse response) {
        if (refreshToken != null) {
            authService.logout(refreshToken);
        }
        clearRefreshCookie(response);
        return ApiResponse.ok(null, traceId());
    }

    @PostMapping("/change-password")
    @Operation(
            summary = "修改密码",
            description = "需 JWT 鉴权。校验旧密码后设置新密码。旧密码错误返回 401（1001）；账户状态冲突返回 409（4009）。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "401",
            description = "旧密码错误（1001 UNAUTHENTICATED）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "409",
            description = "账户状态冲突（4009 STATE_CONFLICT，如账户处于不可改密状态）")
    public ApiResponse<Void> changePassword(@Valid @RequestBody ChangePasswordRequest req) {
        long userId = SecurityUtils.currentUserId();
        authService.changePassword(userId, req.oldPassword(), req.newPassword());
        return ApiResponse.ok(null, traceId());
    }

    private void setRefreshCookie(HttpServletResponse response, String token) {
        // Path 必须为 "/"：STOMP WebSocket 端点位于 /ws，浏览器只有 path=/ 时才会把 refresh_token
        // cookie 附带到 /ws 握手请求上，供 WebSocketAuthInterceptor 完成认证。
        // 安全底线由 HttpOnly + Secure + SameSite=Strict 保证：不会被 JS 读取、不会跨站发送。
        ResponseCookie cookie = ResponseCookie.from(REFRESH_COOKIE, token)
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .path("/")
                .maxAge(7 * 24 * 3600)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private void clearRefreshCookie(HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from(REFRESH_COOKIE, "")
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .path("/")
                .maxAge(0)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private static String traceId() {
        return MDC.get("traceId");
    }

    record RegisterRequest(
            @Schema(description = "用户名，3-64 字符", example = "trader01", requiredMode = Schema.RequiredMode.REQUIRED)
                    @NotBlank
                    @Size(min = 3, max = 64)
                    String username,
            @Schema(description = "邮箱", example = "trader01@example.com", requiredMode = Schema.RequiredMode.REQUIRED)
                    @NotBlank
                    @Email
                    String email,
            @Schema(description = "密码，8-128 字符", example = "p@ssw0rd123", requiredMode = Schema.RequiredMode.REQUIRED)
                    @NotBlank
                    @Size(min = 8, max = 128)
                    String password) {}

    record LoginRequest(
            @Schema(description = "用户名", example = "trader01", requiredMode = Schema.RequiredMode.REQUIRED)
                    @NotBlank
                    String username,
            @Schema(description = "密码", example = "p@ssw0rd123", requiredMode = Schema.RequiredMode.REQUIRED)
                    @NotBlank
                    @Size(max = 128)
                    String password) {}

    record ChangePasswordRequest(
            @Schema(description = "旧密码", example = "p@ssw0rd123", requiredMode = Schema.RequiredMode.REQUIRED)
                    @NotBlank
                    String oldPassword,
            @Schema(description = "新密码，8-128 字符", example = "n3wP@ssw0rd", requiredMode = Schema.RequiredMode.REQUIRED)
                    @NotBlank
                    @Size(min = 8, max = 128)
                    String newPassword) {}

    record TokenResponse(
            @Schema(description = "JWT access token，有效期 15min，前端放 Authorization: Bearer <token>", example = "eyJhbGciOi...")
                    String accessToken,
            @Schema(description = "access token 有效期（秒）", example = "900") long expiresIn) {}
}
