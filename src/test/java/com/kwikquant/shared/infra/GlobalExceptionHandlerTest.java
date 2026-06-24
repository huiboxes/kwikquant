package com.kwikquant.shared.infra;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void handleAccessDenied() {
        ApiResponse<Void> resp = handler.handleAccessDenied(new AccessDeniedException("forbidden"));
        assertEquals(ErrorCode.FORBIDDEN, resp.code());
        assertEquals("access denied", resp.message());
    }

    @Test
    void handleOwnershipViolation() {
        ApiResponse<Void> resp = handler.handleOwnershipViolation(new OwnershipViolationException("account"));
        assertEquals(ErrorCode.FORBIDDEN, resp.code());
        assertEquals("access denied", resp.message());
    }

    @Test
    void handleResourceNotFound() {
        ApiResponse<Void> resp = handler.handleResourceNotFound(new ResourceNotFoundException("order"));
        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, resp.code());
        assertEquals("resource not found", resp.message());
    }

    @Test
    void handleNoResourceFound() {
        ApiResponse<Void> resp = handler.handleNoResourceFound(
                new NoResourceFoundException(org.springframework.http.HttpMethod.GET, "/foo", null));
        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, resp.code());
        assertEquals("resource not found", resp.message());
    }

    @Test
    void handleValidation() {
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "request");
        bindingResult.addError(new FieldError("request", "email", "must not be blank"));
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(null, bindingResult);
        ApiResponse<Void> resp = handler.handleValidation(ex);
        assertEquals(ErrorCode.VALIDATION_FAILED, resp.code());
        assertTrue(resp.message().contains("email"));
    }

    @Test
    void handleIllegalArg() {
        ApiResponse<Void> resp = handler.handleIllegalArg(new IllegalArgumentException("bad input"));
        assertEquals(ErrorCode.VALIDATION_FAILED, resp.code());
    }

    @Test
    void handleExchangeException() {
        ApiResponse<Void> resp = handler.handleExchange(new ExchangeException("timeout", true));
        assertEquals(ErrorCode.EXCHANGE_UNAVAILABLE, resp.code());
        assertEquals("exchange unavailable", resp.message());
    }

    @Test
    void handleUnexpected() {
        ApiResponse<Void> resp = handler.handleUnexpected(new RuntimeException("oops"));
        assertEquals(ErrorCode.INTERNAL_ERROR, resp.code());
        assertEquals("internal error", resp.message());
    }
}
