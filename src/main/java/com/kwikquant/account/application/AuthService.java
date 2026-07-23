package com.kwikquant.account.application;

import com.kwikquant.account.domain.AccountDisabledException;
import com.kwikquant.account.domain.InvalidCredentialsException;
import com.kwikquant.account.domain.InvalidInviteCodeException;
import com.kwikquant.account.domain.InviteCode;
import com.kwikquant.account.domain.PasswordHasher;
import com.kwikquant.account.domain.User;
import com.kwikquant.account.infrastructure.InviteCodeMapper;
import com.kwikquant.account.infrastructure.JwtProvider;
import com.kwikquant.account.infrastructure.RefreshTokenMapper;
import com.kwikquant.account.infrastructure.RefreshTokenMapper.RefreshTokenRow;
import com.kwikquant.account.infrastructure.UserMapper;
import com.kwikquant.shared.infra.Auditable;
import com.kwikquant.shared.types.Exchange;
import io.jsonwebtoken.Claims;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final UserMapper userMapper;
    private final RefreshTokenMapper refreshTokenMapper;
    private final JwtProvider jwtProvider;
    private final InviteCodeMapper inviteCodeMapper;
    private final ExchangeAccountService exchangeAccountService;

    public AuthService(
            UserMapper userMapper,
            RefreshTokenMapper refreshTokenMapper,
            JwtProvider jwtProvider,
            InviteCodeMapper inviteCodeMapper,
            ExchangeAccountService exchangeAccountService) {
        this.userMapper = userMapper;
        this.refreshTokenMapper = refreshTokenMapper;
        this.jwtProvider = jwtProvider;
        this.inviteCodeMapper = inviteCodeMapper;
        this.exchangeAccountService = exchangeAccountService;
    }

    @Transactional
    @Auditable(action = "USER_REGISTERED", targetType = "user", targetId = "#username")
    public AuthResult register(String username, String email, String rawPassword, String inviteCode) {
        // 校验邀请码(存在 + enabled + 未过期 + used < max);任一失败抛 InvalidInviteCodeException
        InviteCode code = inviteCodeMapper.findByCode(inviteCode);
        if (code == null || !code.enabled()) {
            throw new InvalidInviteCodeException();
        }
        if (code.expiresAt() != null && code.expiresAt().isBefore(Instant.now())) {
            throw new InvalidInviteCodeException();
        }
        if (code.usedCount() >= code.maxUses()) {
            throw new InvalidInviteCodeException();
        }

        if (userMapper.findByUsername(username) != null) {
            throw new IllegalArgumentException("username already exists");
        }
        if (userMapper.findByEmail(email) != null) {
            throw new IllegalArgumentException("email already exists");
        }

        User user = new User(username, email, PasswordHasher.hash(rawPassword));
        userMapper.insert(user);

        // 乐观锁消费(WHERE used_count < max_uses AND enabled);并发用尽则返 0,@Transactional 回滚用户创建
        int consumed = inviteCodeMapper.incrementUsedCount(inviteCode);
        if (consumed == 0) {
            throw new InvalidInviteCodeException();
        }

        // 注册即建默认模拟盘账户(OKX 基准交易所,paperTrading=true),用户注册后可直接回测+模拟盘,
        // 无需先手动配账户。新用户 userId 新,不存在 OKX 账户冲突;建账户失败回滚整个注册(含 user+inviteCode),
        // 避免孤儿 user(注册成功但无默认账户)。模拟盘无需 apiKey/apiSecret,传 null。
        exchangeAccountService.create(
                new CreateAccountCommand(user.getId(), Exchange.OKX, "默认模拟盘", null, null, null, true, false));

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
