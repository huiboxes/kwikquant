package com.kwikquant.shared.infra;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import tools.jackson.databind.ObjectMapper;

/**
 * Stateless utility for writing JSON error responses directly to an {@link HttpServletResponse}.
 *
 * <p>This class is intentionally non-Spring-managed; security entry-point / access-denied
 * delegation is handled by {@link SecurityErrorHandler}.
 */
public final class JsonErrorWriter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonErrorWriter() {}

    /**
     * Writes a structured JSON error body to the response.
     *
     * @param response   the servlet response (status and content-type will be set)
     * @param httpStatus HTTP status code
     * @param errorCode  application-specific error code
     * @param message    human-readable error message
     * @throws IOException if the response cannot be written to
     */
    public static void write(HttpServletResponse response, int httpStatus, int errorCode, String message)
            throws IOException {
        response.setStatus(httpStatus);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        String traceId = MDC.get("traceId");
        ApiResponse<Void> body = ApiResponse.error(errorCode, message, traceId);
        try {
            MAPPER.writeValue(response.getOutputStream(), body);
        } catch (Exception fallback) {
            response.getWriter()
                    .write("{\"code\":5001,\"message\":\"internal error\",\"data\":null,\"traceId\":null}");
        }
    }
}
