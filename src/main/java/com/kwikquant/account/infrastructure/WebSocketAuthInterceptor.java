package com.kwikquant.account.infrastructure;

import com.kwikquant.shared.infra.WorkerTokenService;
import com.kwikquant.shared.infra.WorkerTokenService.WorkerTokenEntry;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

@Component
public class WebSocketAuthInterceptor implements HandshakeInterceptor {

    private static final String REFRESH_COOKIE = "refresh_token";
    private static final String WORKER_TOKEN_HEADER = "X-Worker-Token";

    private final JwtProvider jwtProvider;
    private final RefreshTokenMapper refreshTokenMapper;
    private final WorkerTokenService workerTokenService;

    public WebSocketAuthInterceptor(
            JwtProvider jwtProvider,
            RefreshTokenMapper refreshTokenMapper,
            WorkerTokenService workerTokenService) {
        this.jwtProvider = jwtProvider;
        this.refreshTokenMapper = refreshTokenMapper;
        this.workerTokenService = workerTokenService;
    }

    @Override
    public boolean beforeHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Map<String, Object> attributes) {
        if (!(request instanceof ServletServerHttpRequest servletRequest)) {
            return false;
        }
        HttpServletRequest raw = servletRequest.getServletRequest();

        // Round-6 BLOCKER 2 修复:tech-design §3.3 数据格式消歧 — Worker STOMP 走 service_token 路径。
        // 优先检查 X-Worker-Token header;命中即走 WorkerTokenService.validateToken 分流,不 fallback JWT。
        String workerToken = raw.getHeader(WORKER_TOKEN_HEADER);
        if (workerToken != null && !workerToken.isBlank()) {
            WorkerTokenEntry entry = workerTokenService.getEntry(workerToken);
            if (entry != null) {
                attributes.put("userId", String.valueOf(entry.userId()));
                attributes.put("strategyId", entry.strategyId());
                attributes.put("workerTaskType", entry.taskType());
                return true;
            }
            return false; // service_token 提供但无效 → 拒绝,不 fallback
        }

        // Fallback:JWT via refresh cookie(外部用户 Dashboard)
        String token = extractTokenFromCookie(raw);
        if (token != null) {
            Claims claims = jwtProvider.parseToken(token);
            if (claims != null && claims.getId() != null) {
                RefreshTokenMapper.RefreshTokenRow row = refreshTokenMapper.findByJti(claims.getId());
                if (row != null && !row.isRevoked() && !row.isExpired()) {
                    attributes.put("userId", claims.getSubject());
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public void afterHandshake(
            ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler, Exception exception) {}

    private String extractTokenFromCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (REFRESH_COOKIE.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
