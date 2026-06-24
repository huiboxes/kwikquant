package com.kwikquant.shared.infra;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class LoggingFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            MDC.put(MdcKeys.TRACE_ID, resolveTraceId(request));
            MDC.put(MdcKeys.OWNER_USER_ID, resolveOwnerUserId());
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MdcKeys.TRACE_ID);
            MDC.remove(MdcKeys.OWNER_USER_ID);
        }
    }

    private String resolveTraceId(HttpServletRequest request) {
        String traceId = request.getHeader("X-Trace-Id");
        return traceId != null && !traceId.isBlank()
                ? traceId
                : UUID.randomUUID().toString();
    }

    private String resolveOwnerUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
            return auth.getName();
        }
        return null;
    }
}
