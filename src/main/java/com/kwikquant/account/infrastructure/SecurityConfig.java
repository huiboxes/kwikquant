package com.kwikquant.account.infrastructure;

import com.kwikquant.shared.infra.JsonErrorWriter;
import com.kwikquant.shared.infra.McpTokenAuthenticationFilter;
import com.kwikquant.shared.infra.McpTokenService;
import com.kwikquant.shared.infra.WorkerTokenFilter;
import com.kwikquant.shared.infra.WorkerTokenService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtProvider jwtProvider,
            JsonErrorWriter jsonErrorWriter,
            WorkerTokenService workerTokenService,
            McpTokenService mcpTokenService)
            throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.requestMatchers(
                                "/api/v1/auth/register",
                                "/api/v1/auth/login",
                                "/api/v1/auth/refresh",
                                "/actuator/health/**",
                                "/v3/api-docs/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html")
                        .permitAll()
                        // /mcp/** 不放行 permitAll：需 PAT 认证（McpTokenAuthenticationFilter 接管）。
                        // /actuator/metrics、/actuator/prometheus 等需认证——未鉴权暴露 JVM/连接池/业务指标
                        .anyRequest()
                        .authenticated())
                .exceptionHandling(
                        ex -> ex.authenticationEntryPoint(jsonErrorWriter).accessDeniedHandler(jsonErrorWriter))
                // filter 顺序（addFilterBefore 逆序注册，运行时从上到下）：
                //   McpTokenAuthenticationFilter → JwtAuthenticationFilter → WorkerTokenFilter →
                // UsernamePasswordAuthenticationFilter
                // Mcp PAT filter 在最前：/mcp/** 路径 PAT 验证后 setAuth，JwtFilter 见 auth 非 null 跳过（Round-8 深度防御）；
                // 非 /mcp 路径 PAT filter 直通，JwtFilter 接管 /api/v1。
                .addFilterBefore(new JwtAuthenticationFilter(jwtProvider), UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(new WorkerTokenFilter(workerTokenService), JwtAuthenticationFilter.class)
                .addFilterBefore(new McpTokenAuthenticationFilter(mcpTokenService), JwtAuthenticationFilter.class)
                .build();
    }
}
