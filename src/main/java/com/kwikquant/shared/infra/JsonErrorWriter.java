package com.kwikquant.shared.infra;

import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.http.MediaType;

public final class JsonErrorWriter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonErrorWriter() {}

    public static void write(HttpServletResponse response, int httpStatus, int errorCode, String message)
            throws IOException {
        response.setStatus(httpStatus);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        String traceId = MDC.get("traceId");
        ApiResponse<Void> body = ApiResponse.error(errorCode, message, traceId);
        MAPPER.writeValue(response.getOutputStream(), body);
    }
}
