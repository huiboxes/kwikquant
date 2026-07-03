package com.kwikquant.account.infrastructure;

import com.kwikquant.shared.infra.JsonErrorWriter;
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
            HttpSecurity http, JwtProvider jwtProvider, JsonErrorWriter jsonErrorWriter, WorkerTokenService workerTokenService)
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
                        // /actuator/metrics、/actuator/prometheus 等需认证——未鉴权暴露 JVM/连接池/业务指标
                        .anyRequest()
                        .authenticated())
                .exceptionHandling(
                        ex -> ex.authenticationEntryPoint(jsonErrorWriter).accessDeniedHandler(jsonErrorWriter))
                // 先注册 JwtAuthenticationFilter(基于 Spring 内置 UsernamePasswordAuthenticationFilter),
                // 再把 WorkerTokenFilter 挂到 JwtAuthenticationFilter 之前——Worker 端点走 X-Worker-Token 放行,
                // 用户端点走 JWT。顺序反了 Spring Security 会抛"filter class has no registered order"。
                .addFilterBefore(new JwtAuthenticationFilter(jwtProvider), UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(new WorkerTokenFilter(workerTokenService), JwtAuthenticationFilter.class)
                .build();
    }
}
