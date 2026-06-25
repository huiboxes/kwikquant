package com.kwikquant.account.infrastructure;

import static org.junit.jupiter.api.Assertions.*;

import com.kwikquant.account.domain.AccountDisabledException;
import com.kwikquant.account.domain.InvalidCredentialsException;
import com.kwikquant.shared.infra.ApiResponse;
import com.kwikquant.shared.infra.ErrorCode;
import org.junit.jupiter.api.Test;

class AuthErrorAdviceTest {

    private final AuthErrorAdvice advice = new AuthErrorAdvice();

    @Test
    void invalidCredentialsReturns1001() {
        ApiResponse<Void> resp = advice.handleInvalidCredentials(new InvalidCredentialsException());
        assertEquals(ErrorCode.UNAUTHENTICATED, resp.code());
        assertEquals("invalid credentials", resp.message());
    }

    @Test
    void accountDisabledReturns1001() {
        ApiResponse<Void> resp = advice.handleAccountDisabled(new AccountDisabledException());
        assertEquals(ErrorCode.UNAUTHENTICATED, resp.code());
        assertEquals("invalid credentials", resp.message());
    }
}
