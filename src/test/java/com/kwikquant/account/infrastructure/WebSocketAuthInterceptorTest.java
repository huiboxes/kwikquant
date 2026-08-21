package com.kwikquant.account.infrastructure;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.kwikquant.account.application.WsTicketService;
import com.kwikquant.shared.infra.WorkerTokenService;
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
    private WorkerTokenService workerTokenService;
    private WsTicketService wsTicketService;
    private WebSocketAuthInterceptor interceptor;

    @BeforeEach
    void setUp() {
        SecretKey key = Jwts.SIG.HS256.key().build();
        jwtProvider = new JwtProvider(key, Duration.ofMinutes(15), Duration.ofDays(7));
        refreshTokenMapper = mock(RefreshTokenMapper.class);
        workerTokenService = new WorkerTokenService();
        wsTicketService = new WsTicketService();
        interceptor =
                new WebSocketAuthInterceptor(jwtProvider, refreshTokenMapper, workerTokenService, wsTicketService);
    }

    @Test
    void validTicketAllowsHandshake_andIsSingleUse() {
        String ticket = wsTicketService.issue(42L).ticket();
        MockHttpServletRequest httpReq = new MockHttpServletRequest();
        httpReq.setParameter("ticket", ticket);
        var attrs = new java.util.HashMap<String, Object>();

        assertTrue(interceptor.beforeHandshake(new ServletServerHttpRequest(httpReq), null, null, attrs));
        assertEquals("42", attrs.get("userId"));

        // 同一 ticket 二次握手必须拒绝（一次性消费，防重放）
        assertFalse(interceptor.beforeHandshake(
                new ServletServerHttpRequest(httpReq), null, null, new java.util.HashMap<>()));
    }

    @Test
    void invalidTicketRejects_doesNotFallbackToCookie() {
        // 防御性:ticket 提供但无效 → 拒绝,即使 refresh cookie 有效也不 fallback
        var rt = jwtProvider.generateRefreshToken(42L);
        var row = new RefreshTokenMapper.RefreshTokenRow(1L, rt.jti(), 42L, null, rt.expiresAt(), Instant.now());
        when(refreshTokenMapper.findByJti(rt.jti())).thenReturn(row);

        MockHttpServletRequest httpReq = new MockHttpServletRequest();
        httpReq.setParameter("ticket", "not-a-valid-ticket");
        httpReq.setCookies(new jakarta.servlet.http.Cookie("refresh_token", rt.token()));

        assertFalse(interceptor.beforeHandshake(
                new ServletServerHttpRequest(httpReq), null, null, new java.util.HashMap<>()));
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

    @Test
    void validWorkerTokenAllowsHandshakeAndPopulatesAttributes() {
        // X-Worker-Token 命中走 WorkerTokenService 分流,attributes 注入完整身份
        String workerToken = workerTokenService.issueRunnerToken(7L, 42L, "BINANCE", 0L);
        MockHttpServletRequest httpReq = new MockHttpServletRequest();
        httpReq.addHeader("X-Worker-Token", workerToken);
        var attrs = new java.util.HashMap<String, Object>();

        assertTrue(interceptor.beforeHandshake(new ServletServerHttpRequest(httpReq), null, null, attrs));
        assertEquals("42", attrs.get("userId"));
        assertEquals(7L, attrs.get("strategyId"));
        assertEquals("RUNNER", attrs.get("workerTaskType"));
    }

    @Test
    void invalidWorkerTokenRejects_doesNotFallbackToJwt() {
        // 防御性:X-Worker-Token 提供但无效 → 拒绝,不 fallback 到 refresh cookie(防混用攻击)
        var rt = jwtProvider.generateRefreshToken(42L);
        var row = new RefreshTokenMapper.RefreshTokenRow(1L, rt.jti(), 42L, null, rt.expiresAt(), Instant.now());
        when(refreshTokenMapper.findByJti(rt.jti())).thenReturn(row);

        MockHttpServletRequest httpReq = new MockHttpServletRequest();
        httpReq.addHeader("X-Worker-Token", "not-a-valid-worker-token");
        httpReq.setCookies(new jakarta.servlet.http.Cookie("refresh_token", rt.token()));

        assertFalse(interceptor.beforeHandshake(
                new ServletServerHttpRequest(httpReq), null, null, new java.util.HashMap<>()));
    }
}
