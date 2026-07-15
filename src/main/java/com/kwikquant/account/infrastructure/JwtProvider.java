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

    /** 黑名单 cache 容量上限：远超过单个 access token TTL 窗口内可能发生的 logout 次数。 */
    private static final int MAX_REVOKED_TOKENS_CACHE_SIZE = 10_000;

    /** 黑名单 TTL 相对 access token TTL 的富余量，覆盖时钟误差/请求排队等待窗口。 */
    private static final Duration REVOCATION_TTL_MARGIN = Duration.ofMinutes(5);

    private final SecretKey signingKey;
    private final Duration accessTokenTtl;
    private final Duration refreshTokenTtl;

    /**
     * Access token 黑名单（TD-033）。logout 时将 access token jti 加入此 cache，
     * JwtAuthenticationFilter 校验时检查。TTL 基于构造函数注入的 {@code accessTokenTtl} 动态计算
     * （而非硬编码常量），确保黑名单存活时间始终覆盖 access token 的真实有效期——否则运维调整
     * {@code accessTokenTtl} 配置后，黑名单会在 token 自然过期前被淘汰，已登出的 token 可"复活"。
     */
    private final Cache<String, Boolean> revokedAccessTokens;

    public JwtProvider(SecretKey signingKey, Duration accessTokenTtl, Duration refreshTokenTtl) {
        this.signingKey = signingKey;
        this.accessTokenTtl = accessTokenTtl;
        this.refreshTokenTtl = refreshTokenTtl;
        this.revokedAccessTokens = Caffeine.newBuilder()
                .expireAfterWrite(accessTokenTtl.plus(REVOCATION_TTL_MARGIN))
                .maximumSize(MAX_REVOKED_TOKENS_CACHE_SIZE)
                .build();
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
            log.debug(
                    "[jwt] token expired: subject={} expiredAt={}",
                    e.getClaims().getSubject(),
                    e.getClaims().getExpiration());
            return null;
        } catch (MalformedJwtException e) {
            log.warn("[jwt] malformed token: {}", e.getMessage());
            return null;
        } catch (SecurityException e) {
            log.warn("[jwt] signature verification failed: {}", e.getMessage());
            return null;
        } catch (JwtException | IllegalArgumentException e) {
            log.warn(
                    "[jwt] token parse failed: type={} message={}", e.getClass().getSimpleName(), e.getMessage());
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
