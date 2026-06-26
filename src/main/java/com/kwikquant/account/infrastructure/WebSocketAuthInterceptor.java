package com.kwikquant.account.infrastructure;

import io.jsonwebtoken.Claims;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

public class WebSocketAuthInterceptor implements HandshakeInterceptor {

    private static final String REFRESH_COOKIE = "refresh_token";

    private final JwtProvider jwtProvider;
    private final RefreshTokenMapper refreshTokenMapper;

    public WebSocketAuthInterceptor(JwtProvider jwtProvider, RefreshTokenMapper refreshTokenMapper) {
        this.jwtProvider = jwtProvider;
        this.refreshTokenMapper = refreshTokenMapper;
    }

    @Override
    public boolean beforeHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Map<String, Object> attributes) {
        if (request instanceof ServletServerHttpRequest servletRequest) {
            String token = extractTokenFromCookie(servletRequest.getServletRequest());
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
