package com.kwikquant.account.infrastructure;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtProvider jwtProvider;

    public JwtAuthenticationFilter(JwtProvider jwtProvider) {
        this.jwtProvider = jwtProvider;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        // Round-8 深度防御:若上游 filter(WorkerTokenFilter)已 setAuth(Worker service_token 场景),
        // 不再用 JWT 覆盖 — 避免混用请求身份漂移(用户同时传 X-Worker-Token 和 Authorization: Bearer 时,
        // Worker 身份应优先,TradingService 的 loadOwnedAccount 校验会拒绝跨用户,但 Auth 语义应清晰)。
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            String header = request.getHeader("Authorization");
            if (header != null && header.startsWith(BEARER_PREFIX)) {
                String token = header.substring(BEARER_PREFIX.length());
                Claims claims = jwtProvider.parseToken(token);
                if (claims != null) {
                    // TD-033: 检查 access token 是否已被 logout 撤销
                    String jti = claims.getId();
                    if (jwtProvider.isAccessTokenRevoked(jti)) {
                        log.debug("[jwt] revoked access token rejected: jti={} subject={}", jti, claims.getSubject());
                    } else {
                        String userId = claims.getSubject();
                        UsernamePasswordAuthenticationToken auth =
                                new UsernamePasswordAuthenticationToken(userId, null, List.of());
                        SecurityContextHolder.getContext().setAuthentication(auth);
                    }
                }
            }
        }
        try {
            filterChain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
