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
}
