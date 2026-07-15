package com.kwikquant.shared.infra;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 填充 {@link MdcKeys#OWNER_USER_ID} 到 MDC，供日志关联。
 *
 * <p>顺序约束：必须在 Spring Security 的 FilterChainProxy（默认 order {@code -100}，
 * 即 {@code SecurityProperties.DEFAULT_FILTERING_ORDER}）<b>之后</b>执行，否则
 * {@code SecurityContextHolder} 尚未填充，{@code resolveOwnerUserId()} 恒为 null。
 * 当前注册于 order {@code -99}（见 {@link AuditConfig#userContextFilter()}）。
 * 若后续调整 Spring Security 的过滤顺序，须同步复核此处。
 */
public class UserContextFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            String userId = resolveOwnerUserId();
            if (userId != null) {
                MDC.put(MdcKeys.OWNER_USER_ID, userId);
            }
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MdcKeys.OWNER_USER_ID);
        }
    }

    private String resolveOwnerUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !SecurityUtils.ANONYMOUS_PRINCIPAL.equals(auth.getPrincipal())) {
            return auth.getName();
        }
        return null;
    }
}
