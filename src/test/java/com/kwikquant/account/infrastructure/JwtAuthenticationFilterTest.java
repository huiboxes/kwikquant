package com.kwikquant.account.infrastructure;

import static org.junit.jupiter.api.Assertions.*;

import io.jsonwebtoken.Jwts;
import java.time.Duration;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

class JwtAuthenticationFilterTest {

    private JwtProvider jwtProvider;
    private JwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        SecretKey key = Jwts.SIG.HS256.key().build();
        jwtProvider = new JwtProvider(key, Duration.ofMinutes(15), Duration.ofDays(7));
        filter = new JwtAuthenticationFilter(jwtProvider);
        SecurityContextHolder.clearContext();
    }

    @Test
    void validTokenSetsAuthentication() throws Exception {
        String token = jwtProvider.generateAccessToken(42L, "alice");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);

        String[] captured = new String[1];
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            captured[0] = SecurityContextHolder.getContext().getAuthentication().getName();
        });

        assertEquals("42", captured[0]);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void noHeaderPassesThrough() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        boolean[] called = {false};

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            called[0] = true;
            assertNull(SecurityContextHolder.getContext().getAuthentication());
        });

        assertTrue(called[0]);
    }

    @Test
    void invalidTokenPassesThrough() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer invalid.token.here");
        boolean[] called = {false};

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            called[0] = true;
            assertNull(SecurityContextHolder.getContext().getAuthentication());
        });

        assertTrue(called[0]);
    }

    @Test
    void clearsContextAfterException() throws Exception {
        String token = jwtProvider.generateAccessToken(1L, "bob");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);

        assertThrows(
                RuntimeException.class,
                () -> filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
                    assertNotNull(SecurityContextHolder.getContext().getAuthentication());
                    throw new RuntimeException("boom");
                }));

        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }
}
