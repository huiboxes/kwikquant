package com.kwikquant.account.infrastructure;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.security.SecurityException;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JwtProvider {

    private static final Logger log = LoggerFactory.getLogger(JwtProvider.class);

    private final SecretKey signingKey;
    private final Duration accessTokenTtl;
    private final Duration refreshTokenTtl;

    /**
     * Access token 黑名单（TD-033）。logout 时将 access token jti 加入此 cache，
     * JwtAuthenticationFilter 校验时检查。TTL = access token TTL，过期自动淘汰。
     * 单机内存即可：access token 15min TTL，cache 最多容纳该窗口内所有 logout 的 jti。
     */
    private final Cache<String, Boolean> revokedAccessTokens = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(20)) // 略大于 access token TTL (15min)
            .maximumSize(10_000)
            .build();

    public JwtProvider(SecretKey signingKey, Duration accessTokenTtl, Duration refreshTokenTtl) {
        this.signingKey = signingKey;
        this.accessTokenTtl = accessTokenTtl;
        this.refreshTokenTtl = refreshTokenTtl;
    }

    public String generateAccessToken(long userId, String username) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .id(UUID.randomUUID().toString())
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
        } catch (ExpiredJwtException e) {
            log.debug("[jwt] token expired: subject={} expiredAt={}", e.getClaims().getSubject(), e.getClaims().getExpiration());
            return null;
        } catch (MalformedJwtException e) {
            log.warn("[jwt] malformed token: {}", e.getMessage());
            return null;
        } catch (SecurityException e) {
            log.warn("[jwt] signature verification failed: {}", e.getMessage());
            return null;
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("[jwt] token parse failed: type={} message={}", e.getClass().getSimpleName(), e.getMessage());
            return null;
        }
    }

    public Duration getAccessTokenTtl() {
        return accessTokenTtl;
    }

    /** TD-033: 将 access token jti 加入黑名单。logout 时调用。 */
    public void revokeAccessToken(String jti) {
        if (jti != null) {
            revokedAccessTokens.put(jti, Boolean.TRUE);
            log.info("[jwt] access token revoked: jti={}", jti);
        }
    }

    /** TD-033: 检查 access token jti 是否已被撤销。JwtAuthenticationFilter 调用。 */
    public boolean isAccessTokenRevoked(String jti) {
        return jti != null && revokedAccessTokens.getIfPresent(jti) != null;
    }

    public record RefreshTokenResult(String token, String jti, Instant expiresAt) {}
}
