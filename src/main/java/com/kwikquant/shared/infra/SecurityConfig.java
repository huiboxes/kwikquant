package com.kwikquant.shared.infra;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Wave 1 骨架配置 — 仅占位，无任何真实鉴权。
 *
 * TODO(Wave 2): 替换为生产级配置：
 * - authorizeHttpRequests 默认 denyAll，按模块显式放行（/api/v1/auth/** 公开，其余 authenticated()）
 * - CSRF：对走 httpOnly Cookie 的端点（/auth/refresh、/auth/logout）必须开启，纯 JWT 的可忽略
 * - 会话 STATELESS（Access Token 内存 + Refresh Token httpOnly Cookie，不创建 HttpSession）
 * - JwtAuthenticationFilter 接入 FilterChainProxy，JsonErrorWriter 输出 401/403
 * - CORS：限定受信前端域，不使用 *
 */
@Configuration
class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .build();
    }
}
