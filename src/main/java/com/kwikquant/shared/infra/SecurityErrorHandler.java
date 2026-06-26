package com.kwikquant.shared.infra;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/**
 * Delegates Spring Security authentication/authorization failure to
 * {@link JsonErrorWriter} for consistent JSON error responses.
 */
@Component
public class SecurityErrorHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    @Override
    public void commence(@NonNull HttpServletRequest request, HttpServletResponse response, AuthenticationException e)
            throws IOException {
        JsonErrorWriter.write(response, HttpStatus.UNAUTHORIZED.value(), ErrorCode.UNAUTHENTICATED, "unauthenticated");
    }

    @Override
    public void handle(@NonNull HttpServletRequest request, HttpServletResponse response, AccessDeniedException e)
            throws IOException {
        JsonErrorWriter.write(response, HttpStatus.FORBIDDEN.value(), ErrorCode.FORBIDDEN, "access denied");
    }
}
