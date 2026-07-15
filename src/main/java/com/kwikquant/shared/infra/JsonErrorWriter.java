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

    /**
     * 序列化失败时的静态兜底 payload（预先编码好，不依赖任何运行时序列化）。对应
     * {@link ErrorCode#INTERNAL_ERROR}。
     */
    private static final byte[] FALLBACK_PAYLOAD =
            "{\"code\":5001,\"message\":\"internal error\",\"data\":null,\"traceId\":null}"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);

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
        String traceId = MDC.get(MdcKeys.TRACE_ID);
        ApiResponse<Void> body = ApiResponse.error(errorCode, message, traceId);
        byte[] payload;
        try {
            payload = MAPPER.writeValueAsBytes(body);
        } catch (Exception fallback) {
            // 序列化失败兜底：写预先编码好的静态字节数组，避免再次调用可能失败的序列化逻辑，
            // 且始终走 getOutputStream()（不混用 getWriter()，二者互斥，混用会抛 IllegalStateException）。
            payload = FALLBACK_PAYLOAD;
        }
        response.getOutputStream().write(payload);
    }
}
