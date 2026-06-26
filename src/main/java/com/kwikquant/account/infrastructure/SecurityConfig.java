package com.kwikquant.account.infrastructure;

import com.kwikquant.shared.infra.SecurityErrorHandler;
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
            HttpSecurity http, JwtProvider jwtProvider, SecurityErrorHandler securityErrorHandler) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.requestMatchers(
                                "/api/v1/auth/register", "/api/v1/auth/login", "/api/v1/auth/refresh", "/actuator/**")
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                .exceptionHandling(ex ->
                        ex.authenticationEntryPoint(securityErrorHandler).accessDeniedHandler(securityErrorHandler))
                .addFilterBefore(new JwtAuthenticationFilter(jwtProvider), UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
