package com.kwikquant.account.infrastructure;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;

public class JwtProvider {

    private final SecretKey signingKey;
    private final Duration accessTokenTtl;
    private final Duration refreshTokenTtl;

    public JwtProvider(SecretKey signingKey, Duration accessTokenTtl, Duration refreshTokenTtl) {
        this.signingKey = signingKey;
        this.accessTokenTtl = accessTokenTtl;
        this.refreshTokenTtl = refreshTokenTtl;
    }

    public String generateAccessToken(long userId, String username) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("username", username)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(accessTokenTtl)))
                .signWith(signingKey)
                .compact();
    }

    public RefreshTokenResult generateRefreshToken(long userId) {
        Instant now = Instant.now();
        String jti = UUID.randomUUID().toString();
        Instant expiresAt = now.plus(refreshTokenTtl);
        String token = Jwts.builder()
                .subject(String.valueOf(userId))
                .id(jti)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(signingKey)
                .compact();
        return new RefreshTokenResult(token, jti, expiresAt);
    }

    public Claims parseToken(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }

    public Duration getAccessTokenTtl() {
        return accessTokenTtl;
    }

    public record RefreshTokenResult(String token, String jti, Instant expiresAt) {}
}
