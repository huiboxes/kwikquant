package com.kwikquant.account.domain;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ExceptionTest {

    @Test
    void invalidCredentials() {
        InvalidCredentialsException e = new InvalidCredentialsException();
        assertEquals("invalid credentials", e.getMessage());
    }

    @Test
    void accountDisabled() {
        AccountDisabledException e = new AccountDisabledException();
        assertEquals("account disabled", e.getMessage());
    }
}
