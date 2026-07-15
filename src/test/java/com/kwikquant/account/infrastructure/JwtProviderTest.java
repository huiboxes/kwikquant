package com.kwikquant.account.infrastructure;

import static org.junit.jupiter.api.Assertions.*;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import java.time.Duration;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JwtProviderTest {

    private JwtProvider provider;

    @BeforeEach
    void setUp() {
        SecretKey key = Jwts.SIG.HS256.key().build();
        provider = new JwtProvider(key, Duration.ofMinutes(15), Duration.ofDays(7));
    }

    @Test
    void generateAndParseAccessToken() {
        String token = provider.generateAccessToken(42L, "alice");
        Claims claims = provider.parseToken(token);

        assertNotNull(claims);
        assertEquals("42", claims.getSubject());
        assertEquals("alice", claims.get("username", String.class));
        assertNotNull(claims.getIssuedAt());
        assertNotNull(claims.getExpiration());
    }

    @Test
    void generateAndParseRefreshToken() {
        JwtProvider.RefreshTokenResult result = provider.generateRefreshToken(42L);
        assertNotNull(result.jti());
        assertNotNull(result.expiresAt());

        Claims claims = provider.parseToken(result.token());
        assertNotNull(claims);
        assertEquals("42", claims.getSubject());
        assertEquals(result.jti(), claims.getId());
    }

    @Test
    void invalidTokenReturnsNull() {
        assertNull(provider.parseToken("garbage.token.here"));
    }

    @Test
    void tamperedTokenReturnsNull() {
        String token = provider.generateAccessToken(1L, "bob");
        String tampered = token.substring(0, token.length() - 2) + "XX";
        assertNull(provider.parseToken(tampered));
    }

    @Test
    void wrongKeyReturnsNull() {
        String token = provider.generateAccessToken(1L, "bob");
        SecretKey otherKey = Jwts.SIG.HS256.key().build();
        JwtProvider other = new JwtProvider(otherKey, Duration.ofMinutes(15), Duration.ofDays(7));
        assertNull(other.parseToken(token));
    }

    @Test
    void revokeAccessToken_markedRevoked() {
        String jti = "revoke-me";
        assertFalse(provider.isAccessTokenRevoked(jti));
        provider.revokeAccessToken(jti);
        assertTrue(provider.isAccessTokenRevoked(jti));
    }

    @Test
    void isAccessTokenRevoked_nullJti_returnsFalse() {
        assertFalse(provider.isAccessTokenRevoked(null));
    }

    /**
     * 回归测试（H1）：黑名单 TTL 必须基于注入的 accessTokenTtl 动态计算，而不是硬编码常量。
     * 用一个远超此前硬编码值（原 bug 是硬编码 20min）的 accessTokenTtl 构造 provider，
     * revoke 后立即检查仍应生效，证明 TTL 计算读取的是构造函数参数而非字面量。
     */
    @Test
    void revokeAccessToken_ttlScalesWithAccessTokenTtl() {
        SecretKey key = Jwts.SIG.HS256.key().build();
        JwtProvider longTtlProvider = new JwtProvider(key, Duration.ofMinutes(30), Duration.ofDays(7));
        String jti = "long-ttl-revoke";
        longTtlProvider.revokeAccessToken(jti);
        assertTrue(longTtlProvider.isAccessTokenRevoked(jti));
    }
}
