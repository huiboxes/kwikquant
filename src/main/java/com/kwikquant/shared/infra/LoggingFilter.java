package com.kwikquant.shared.infra;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

public class LoggingFilter extends OncePerRequestFilter {

    private static final Pattern TRACE_ID_PATTERN = Pattern.compile("[a-zA-Z0-9\\-]{1,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            MDC.put(MdcKeys.TRACE_ID, resolveTraceId(request));
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MdcKeys.TRACE_ID);
        }
    }

    private String resolveTraceId(HttpServletRequest request) {
        String traceId = request.getHeader("X-Trace-Id");
        if (traceId != null && TRACE_ID_PATTERN.matcher(traceId).matches()) {
            return traceId;
        }
        return UUID.randomUUID().toString();
    }
}
