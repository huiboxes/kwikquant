package com.kwikquant.account.application;

import com.kwikquant.account.domain.AccountDisabledException;
import com.kwikquant.account.domain.InvalidCredentialsException;
import com.kwikquant.account.domain.PasswordHasher;
import com.kwikquant.account.domain.User;
import com.kwikquant.account.infrastructure.JwtProvider;
import com.kwikquant.account.infrastructure.RefreshTokenMapper;
import com.kwikquant.account.infrastructure.RefreshTokenMapper.RefreshTokenRow;
import com.kwikquant.account.infrastructure.UserMapper;
import com.kwikquant.shared.infra.Auditable;
import io.jsonwebtoken.Claims;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final UserMapper userMapper;
    private final RefreshTokenMapper refreshTokenMapper;
    private final JwtProvider jwtProvider;

    public AuthService(UserMapper userMapper, RefreshTokenMapper refreshTokenMapper, JwtProvider jwtProvider) {
        this.userMapper = userMapper;
        this.refreshTokenMapper = refreshTokenMapper;
        this.jwtProvider = jwtProvider;
    }

    @Transactional
    @Auditable(action = "USER_REGISTERED", targetType = "user", targetId = "#username")
    public AuthResult register(String username, String email, String rawPassword) {
        if (userMapper.findByUsername(username) != null) {
            throw new IllegalArgumentException("username already exists");
        }
        if (userMapper.findByEmail(email) != null) {
            throw new IllegalArgumentException("email already exists");
        }

        User user = new User(username, email, PasswordHasher.hash(rawPassword));
        userMapper.insert(user);

        return issueTokens(user.getId(), username);
    }

    @Transactional
    @Auditable(action = "USER_LOGIN", targetType = "user", targetId = "#username")
    public AuthResult login(String username, String rawPassword) {
        User user = userMapper.findByUsername(username);
        if (user == null) {
            throw new InvalidCredentialsException();
        }
        if (!user.isEnabled()) {
            throw new AccountDisabledException();
        }
        if (!PasswordHasher.verify(rawPassword, user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }

        return issueTokens(user.getId(), username);
    }

    @Transactional
    @Auditable(action = "TOKEN_REFRESHED", targetType = "user")
    public AuthResult refresh(String refreshTokenStr) {
        Claims claims = jwtProvider.parseToken(refreshTokenStr);
        if (claims == null) {
            throw new InvalidCredentialsException();
        }

        String jti = claims.getId();
        if (jti == null) {
            throw new InvalidCredentialsException();
        }

        RefreshTokenRow row = refreshTokenMapper.findByJti(jti);
        if (row == null || row.isRevoked() || row.isExpired()) {
            throw new InvalidCredentialsException();
        }

        int revoked = refreshTokenMapper.revokeByJti(jti);
        if (revoked == 0) {
            throw new InvalidCredentialsException();
        }

        long userId = Long.parseLong(claims.getSubject());
        User user = userMapper.findById(userId);
        if (user == null || !user.isEnabled()) {
            throw new InvalidCredentialsException();
        }

        return issueTokens(userId, user.getUsername());
    }

    @Transactional
    @Auditable(action = "USER_LOGOUT", targetType = "user")
    public void logout(String refreshTokenStr) {
        Claims claims = jwtProvider.parseToken(refreshTokenStr);
        if (claims != null && claims.getId() != null) {
            refreshTokenMapper.revokeByJti(claims.getId());
        }
    }

    @Transactional
    @Auditable(action = "PASSWORD_CHANGED", targetType = "user")
    public void changePassword(long userId, String oldPassword, String newPassword) {
        User user = userMapper.findById(userId);
        if (user == null) {
            throw new InvalidCredentialsException();
        }
        if (!PasswordHasher.verify(oldPassword, user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }

        // 深度防御消费：updatePassword WHERE 含 id，返回 0 = 并发已删除用户（Round 4 对齐 Round 3 深防覆盖）
        int updated = userMapper.updatePassword(userId, PasswordHasher.hash(newPassword));
        if (updated == 0) {
            throw new com.kwikquant.shared.infra.ResourceStateConflictException("user " + userId);
        }
        refreshTokenMapper.revokeAllByUserId(userId);
    }

    private AuthResult issueTokens(long userId, String username) {
        String accessToken = jwtProvider.generateAccessToken(userId, username);
        JwtProvider.RefreshTokenResult rt = jwtProvider.generateRefreshToken(userId);
        refreshTokenMapper.insert(new RefreshTokenRow(rt.jti(), userId, rt.expiresAt()));
        return new AuthResult(
                accessToken, rt.token(), jwtProvider.getAccessTokenTtl().getSeconds());
    }

    public record AuthResult(String accessToken, String refreshToken, long expiresIn) {}
}
