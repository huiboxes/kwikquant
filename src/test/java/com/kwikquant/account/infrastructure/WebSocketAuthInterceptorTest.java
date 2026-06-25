package com.kwikquant.account.infrastructure;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.jsonwebtoken.Jwts;
import java.time.Duration;
import java.time.Instant;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;

class WebSocketAuthInterceptorTest {

    private JwtProvider jwtProvider;
    private RefreshTokenMapper refreshTokenMapper;
    private WebSocketAuthInterceptor interceptor;

    @BeforeEach
    void setUp() {
        SecretKey key = Jwts.SIG.HS256.key().build();
        jwtProvider = new JwtProvider(key, Duration.ofMinutes(15), Duration.ofDays(7));
        refreshTokenMapper = mock(RefreshTokenMapper.class);
        interceptor = new WebSocketAuthInterceptor(jwtProvider, refreshTokenMapper);
    }

    @Test
    void validNonRevokedTokenAllowsHandshake() {
        var rt = jwtProvider.generateRefreshToken(42L);
        var row = new RefreshTokenMapper.RefreshTokenRow(1L, rt.jti(), 42L, null, rt.expiresAt(), Instant.now());
        when(refreshTokenMapper.findByJti(rt.jti())).thenReturn(row);

        MockHttpServletRequest httpReq = new MockHttpServletRequest();
        httpReq.setCookies(new jakarta.servlet.http.Cookie("refresh_token", rt.token()));
        var attrs = new java.util.HashMap<String, Object>();

        assertTrue(interceptor.beforeHandshake(new ServletServerHttpRequest(httpReq), null, null, attrs));
        assertEquals("42", attrs.get("userId"));
    }

    @Test
    void revokedTokenRejectsHandshake() {
        var rt = jwtProvider.generateRefreshToken(42L);
        var row =
                new RefreshTokenMapper.RefreshTokenRow(1L, rt.jti(), 42L, Instant.now(), rt.expiresAt(), Instant.now());
        when(refreshTokenMapper.findByJti(rt.jti())).thenReturn(row);

        MockHttpServletRequest httpReq = new MockHttpServletRequest();
        httpReq.setCookies(new jakarta.servlet.http.Cookie("refresh_token", rt.token()));

        assertFalse(interceptor.beforeHandshake(
                new ServletServerHttpRequest(httpReq), null, null, new java.util.HashMap<>()));
    }

    @Test
    void noCookieRejectsHandshake() {
        MockHttpServletRequest httpReq = new MockHttpServletRequest();
        assertFalse(interceptor.beforeHandshake(
                new ServletServerHttpRequest(httpReq), null, null, new java.util.HashMap<>()));
    }

    @Test
    void invalidTokenRejectsHandshake() {
        MockHttpServletRequest httpReq = new MockHttpServletRequest();
        httpReq.setCookies(new jakarta.servlet.http.Cookie("refresh_token", "invalid.jwt"));
        assertFalse(interceptor.beforeHandshake(
                new ServletServerHttpRequest(httpReq), null, null, new java.util.HashMap<>()));
    }
}
