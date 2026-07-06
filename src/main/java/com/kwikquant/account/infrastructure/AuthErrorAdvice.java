package com.kwikquant.account.infrastructure;

import com.kwikquant.account.domain.AccountDisabledException;
import com.kwikquant.account.domain.InvalidCredentialsException;
import com.kwikquant.account.domain.InvalidInviteCodeException;
import com.kwikquant.shared.infra.ApiResponse;
import com.kwikquant.shared.infra.ErrorCode;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackages = "com.kwikquant.account")
@Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
public class AuthErrorAdvice {

    @ExceptionHandler(InvalidCredentialsException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ApiResponse<Void> handleInvalidCredentials(InvalidCredentialsException e) {
        return ApiResponse.error(ErrorCode.UNAUTHENTICATED, "invalid credentials", traceId());
    }

    @ExceptionHandler(AccountDisabledException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ApiResponse<Void> handleAccountDisabled(AccountDisabledException e) {
        return ApiResponse.error(ErrorCode.UNAUTHENTICATED, "invalid credentials", traceId());
    }

    @ExceptionHandler(InvalidInviteCodeException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleInvalidInviteCode(InvalidInviteCodeException e) {
        return ApiResponse.error(ErrorCode.INVITE_CODE_INVALID, "invalid invite code", traceId());
    }

    private static String traceId() {
        return MDC.get("traceId");
    }
}
