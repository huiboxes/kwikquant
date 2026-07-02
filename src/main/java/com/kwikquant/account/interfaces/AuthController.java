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

@RestController
@RequestMapping("/api/v1/auth")
class AuthController {

    private static final String REFRESH_COOKIE = "refresh_token";

    private final AuthService authService;

    AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public ApiResponse<TokenResponse> register(@Valid @RequestBody RegisterRequest req, HttpServletResponse response) {
        AuthResult result = authService.register(req.username(), req.email(), req.password());
        setRefreshCookie(response, result.refreshToken());
        return ApiResponse.ok(new TokenResponse(result.accessToken(), result.expiresIn()), traceId());
    }

    @PostMapping("/login")
    public ApiResponse<TokenResponse> login(@Valid @RequestBody LoginRequest req, HttpServletResponse response) {
        AuthResult result = authService.login(req.username(), req.password());
        setRefreshCookie(response, result.refreshToken());
        return ApiResponse.ok(new TokenResponse(result.accessToken(), result.expiresIn()), traceId());
    }

    @PostMapping("/refresh")
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
    public ApiResponse<Void> logout(
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken, HttpServletResponse response) {
        if (refreshToken != null) {
            authService.logout(refreshToken);
        }
        clearRefreshCookie(response);
        return ApiResponse.ok(null, traceId());
    }

    @PostMapping("/change-password")
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
            @NotBlank @Size(min = 3, max = 64) String username,
            @NotBlank @Email String email,
            @NotBlank @Size(min = 8, max = 128) String password) {}

    record LoginRequest(@NotBlank String username, @NotBlank @Size(max = 128) String password) {}

    record ChangePasswordRequest(
            @NotBlank String oldPassword, @NotBlank @Size(min = 8, max = 128) String newPassword) {}

    record TokenResponse(String accessToken, long expiresIn) {}
}
