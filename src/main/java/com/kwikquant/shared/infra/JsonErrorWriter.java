package com.kwikquant.shared.infra;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class JsonErrorWriter implements AuthenticationEntryPoint, AccessDeniedHandler {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException e)
            throws IOException {
        write(response, 401, ErrorCode.UNAUTHENTICATED, "unauthenticated");
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException e)
            throws IOException {
        write(response, 403, ErrorCode.FORBIDDEN, "access denied");
    }

    public static void write(HttpServletResponse response, int httpStatus, int errorCode, String message)
            throws IOException {
        response.setStatus(httpStatus);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        String traceId = MDC.get("traceId");
        ApiResponse<Void> body = ApiResponse.error(errorCode, message, traceId);
        try {
            MAPPER.writeValue(response.getOutputStream(), body);
        } catch (Exception fallback) {
            response.getWriter().write("{\"code\":5001,\"message\":\"internal error\",\"data\":null,\"traceId\":null}");
        }
    }
}
