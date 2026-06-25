package com.kwikquant.shared.infra;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class SecurityUtilsTest {

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void returnsCurrentUserId() {
        var auth = new UsernamePasswordAuthenticationToken("42", null);
        SecurityContextHolder.getContext().setAuthentication(auth);

        assertEquals(42L, SecurityUtils.currentUserId());
    }

    @Test
    void throwsWhenNoAuth() {
        assertThrows(Exception.class, SecurityUtils::currentUserId);
    }
}
