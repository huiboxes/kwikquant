package com.kwikquant.shared.infra;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class SecurityUtilsTest {

    @BeforeEach
    @AfterEach
    void clearContext() {
        // Round-6:WorkerTokenFilter 现在 setAuthentication;跑全 suite 时其他 SpringBootTest 场景
        // 通过 filter 可能污染 SecurityContextHolder(SecurityContextPersistenceFilter 未介入的
        // integration test 上下文)。@BeforeEach 显式清理,防 test order 相关 flake。
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
