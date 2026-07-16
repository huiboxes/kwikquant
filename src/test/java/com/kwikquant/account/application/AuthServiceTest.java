package com.kwikquant.account.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

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
import com.kwikquant.shared.types.Exchange;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import java.time.Duration;
import java.time.Instant;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AuthServiceTest {

    private UserMapper userMapper;
    private RefreshTokenMapper refreshTokenMapper;
    private JwtProvider jwtProvider;
    private InviteCodeMapper inviteCodeMapper;
    private ExchangeAccountService exchangeAccountService;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        userMapper = mock(UserMapper.class);
        refreshTokenMapper = mock(RefreshTokenMapper.class);
        inviteCodeMapper = mock(InviteCodeMapper.class);
        exchangeAccountService = mock(ExchangeAccountService.class);
        SecretKey key = Jwts.SIG.HS256.key().build();
        jwtProvider = new JwtProvider(key, Duration.ofMinutes(15), Duration.ofDays(7));
        authService = new AuthService(
                userMapper, refreshTokenMapper, jwtProvider, inviteCodeMapper, exchangeAccountService);
    }

    /** 有效邀请码:max=100, used=0, 永不过期, enabled。 */
    private static InviteCode validInviteCode() {
        return new InviteCode("KWIK-DEV-001", 100, 0, null, true, Instant.now());
    }

    @Test
    void registerSuccess() {
        when(inviteCodeMapper.findByCode("KWIK-DEV-001")).thenReturn(validInviteCode());
        when(inviteCodeMapper.incrementUsedCount("KWIK-DEV-001")).thenReturn(1);
        when(userMapper.findByUsername("alice")).thenReturn(null);
        when(userMapper.findByEmail("alice@test.com")).thenReturn(null);
        doAnswer(inv -> {
                    User u = inv.getArgument(0);
                    u.setId(1L);
                    return null;
                })
                .when(userMapper)
                .insert(any(User.class));

        AuthService.AuthResult result = authService.register("alice", "alice@test.com", "password123", "KWIK-DEV-001");

        assertNotNull(result.accessToken());
        assertNotNull(result.refreshToken());
        assertTrue(result.expiresIn() > 0);
        verify(userMapper).insert(any(User.class));
        verify(refreshTokenMapper).insert(any(RefreshTokenRow.class));
        verify(inviteCodeMapper).incrementUsedCount("KWIK-DEV-001");
    }

    @Test
    void registerCreatesDefaultOkxPaperAccount() {
        // 注册即建默认模拟盘账户:OKX 基准交易所,paperTrading=true,无需 apiKey/apiSecret。
        // 用户注册后可直接回测+模拟盘,无需先手动配账户。
        when(inviteCodeMapper.findByCode("KWIK-DEV-001")).thenReturn(validInviteCode());
        when(inviteCodeMapper.incrementUsedCount("KWIK-DEV-001")).thenReturn(1);
        when(userMapper.findByUsername("alice")).thenReturn(null);
        when(userMapper.findByEmail("alice@test.com")).thenReturn(null);
        doAnswer(inv -> {
                    User u = inv.getArgument(0);
                    u.setId(7L);
                    return null;
                })
                .when(userMapper)
                .insert(any(User.class));

        authService.register("alice", "alice@test.com", "password123", "KWIK-DEV-001");

        verify(exchangeAccountService)
                .create(argThat(cmd -> cmd.userId() == 7L
                        && cmd.exchange() == Exchange.OKX
                        && cmd.paperTrading()
                        && cmd.apiKey() == null
                        && cmd.apiSecret() == null));
    }

    @Test
    void registerPaperAccountFailureRollsBackRegistration() {
        // 建默认模拟盘账户失败 → 回滚整个注册(含 user + inviteCode),避免孤儿 user。
        // AuthService 不捕获 create 异常,直接向上抛 → @Transactional 回滚。
        when(inviteCodeMapper.findByCode("KWIK-DEV-001")).thenReturn(validInviteCode());
        when(inviteCodeMapper.incrementUsedCount("KWIK-DEV-001")).thenReturn(1);
        when(userMapper.findByUsername("alice")).thenReturn(null);
        when(userMapper.findByEmail("alice@test.com")).thenReturn(null);
        doAnswer(inv -> {
                    User u = inv.getArgument(0);
                    u.setId(7L);
                    return null;
                })
                .when(userMapper)
                .insert(any(User.class));
        doThrow(new RuntimeException("paper balance init failed"))
                .when(exchangeAccountService)
                .create(any(CreateAccountCommand.class));

        assertThrows(
                RuntimeException.class,
                () -> authService.register("alice", "alice@test.com", "password123", "KWIK-DEV-001"));
    }

    @Test
    void registerDuplicateUsernameThrows() {
        when(inviteCodeMapper.findByCode("KWIK-DEV-001")).thenReturn(validInviteCode());
        when(userMapper.findByUsername("alice")).thenReturn(new User());

        assertThrows(
                IllegalArgumentException.class,
                () -> authService.register("alice", "alice@test.com", "password123", "KWIK-DEV-001"));
    }

    @Test
    void registerDuplicateEmailThrows() {
        when(inviteCodeMapper.findByCode("KWIK-DEV-001")).thenReturn(validInviteCode());
        when(userMapper.findByUsername("alice")).thenReturn(null);
        when(userMapper.findByEmail("alice@test.com")).thenReturn(new User());

        assertThrows(
                IllegalArgumentException.class,
                () -> authService.register("alice", "alice@test.com", "password123", "KWIK-DEV-001"));
    }

    @Test
    void registerNullInviteCodeThrows() {
        when(inviteCodeMapper.findByCode("UNKNOWN")).thenReturn(null);

        assertThrows(
                InvalidInviteCodeException.class,
                () -> authService.register("alice", "alice@test.com", "password123", "UNKNOWN"));
    }

    @Test
    void registerDisabledInviteCodeThrows() {
        InviteCode disabled = new InviteCode("KWIK-DISABLED", 100, 0, null, false, Instant.now());
        when(inviteCodeMapper.findByCode("KWIK-DISABLED")).thenReturn(disabled);

        assertThrows(
                InvalidInviteCodeException.class,
                () -> authService.register("alice", "alice@test.com", "password123", "KWIK-DISABLED"));
    }

    @Test
    void registerExpiredInviteCodeThrows() {
        InviteCode expired =
                new InviteCode("KWIK-EXPIRED", 100, 0, Instant.now().minusSeconds(60), true, Instant.now());
        when(inviteCodeMapper.findByCode("KWIK-EXPIRED")).thenReturn(expired);

        assertThrows(
                InvalidInviteCodeException.class,
                () -> authService.register("alice", "alice@test.com", "password123", "KWIK-EXPIRED"));
    }

    @Test
    void registerExhaustedInviteCodeThrows() {
        // usedCount >= maxUses(已用尽)
        InviteCode exhausted = new InviteCode("KWIK-FULL", 1, 1, null, true, Instant.now());
        when(inviteCodeMapper.findByCode("KWIK-FULL")).thenReturn(exhausted);

        assertThrows(
                InvalidInviteCodeException.class,
                () -> authService.register("alice", "alice@test.com", "password123", "KWIK-FULL"));
    }

    @Test
    void registerConcurrentInviteCodeExhaustedThrows() {
        // 校验通过(getByCode 返有效码),但并发 incrementUsedCount 返 0(被别人抢光)→ 抛 InvalidInviteCodeException
        // @Transactional 在集成测试验证回滚;此处只验证逻辑抛对异常
        when(inviteCodeMapper.findByCode("KWIK-DEV-001")).thenReturn(validInviteCode());
        when(inviteCodeMapper.incrementUsedCount("KWIK-DEV-001")).thenReturn(0);
        when(userMapper.findByUsername("alice")).thenReturn(null);
        when(userMapper.findByEmail("alice@test.com")).thenReturn(null);

        assertThrows(
                InvalidInviteCodeException.class,
                () -> authService.register("alice", "alice@test.com", "password123", "KWIK-DEV-001"));
    }

    @Test
    void loginSuccess() {
        User user = new User("alice", "alice@test.com", PasswordHasher.hash("password123"));
        user.setId(42L);
        when(userMapper.findByUsername("alice")).thenReturn(user);

        AuthService.AuthResult result = authService.login("alice", "password123");

        assertNotNull(result.accessToken());
        Claims claims = jwtProvider.parseToken(result.accessToken());
        assertEquals("42", claims.getSubject());
    }

    @Test
    void loginWrongPasswordThrows() {
        User user = new User("alice", "alice@test.com", PasswordHasher.hash("correct"));
        user.setId(1L);
        when(userMapper.findByUsername("alice")).thenReturn(user);

        assertThrows(InvalidCredentialsException.class, () -> authService.login("alice", "wrong"));
    }

    @Test
    void loginUnknownUserThrows() {
        when(userMapper.findByUsername("nobody")).thenReturn(null);

        assertThrows(InvalidCredentialsException.class, () -> authService.login("nobody", "pw"));
    }

    @Test
    void loginDisabledUserThrows() {
        User user = new User("alice", "alice@test.com", PasswordHasher.hash("pw"));
        user.setId(1L);
        user.setEnabled(false);
        when(userMapper.findByUsername("alice")).thenReturn(user);

        assertThrows(
                com.kwikquant.account.domain.AccountDisabledException.class, () -> authService.login("alice", "pw"));
    }

    @Test
    void refreshSuccess() {
        JwtProvider.RefreshTokenResult rt = jwtProvider.generateRefreshToken(42L);
        RefreshTokenRow row = new RefreshTokenRow(1L, rt.jti(), 42L, null, rt.expiresAt(), Instant.now());
        when(refreshTokenMapper.findByJti(rt.jti())).thenReturn(row);
        when(refreshTokenMapper.revokeByJti(rt.jti())).thenReturn(1);
        User user = new User("alice", "alice@test.com", "hash");
        user.setId(42L);
        when(userMapper.findById(42L)).thenReturn(user);

        AuthService.AuthResult result = authService.refresh(rt.token());

        assertNotNull(result.accessToken());
        verify(refreshTokenMapper).revokeByJti(rt.jti());
    }

    @Test
    void refreshRevokedTokenThrows() {
        JwtProvider.RefreshTokenResult rt = jwtProvider.generateRefreshToken(42L);
        RefreshTokenRow row = new RefreshTokenRow(1L, rt.jti(), 42L, Instant.now(), rt.expiresAt(), Instant.now());
        when(refreshTokenMapper.findByJti(rt.jti())).thenReturn(row);

        assertThrows(InvalidCredentialsException.class, () -> authService.refresh(rt.token()));
    }

    @Test
    void refreshInvalidTokenThrows() {
        assertThrows(InvalidCredentialsException.class, () -> authService.refresh("garbage"));
    }

    @Test
    void refreshConcurrentUseThrows() {
        JwtProvider.RefreshTokenResult rt = jwtProvider.generateRefreshToken(42L);
        RefreshTokenRow row = new RefreshTokenRow(1L, rt.jti(), 42L, null, rt.expiresAt(), Instant.now());
        when(refreshTokenMapper.findByJti(rt.jti())).thenReturn(row);
        when(refreshTokenMapper.revokeByJti(rt.jti())).thenReturn(0);

        assertThrows(InvalidCredentialsException.class, () -> authService.refresh(rt.token()));
    }

    @Test
    void logoutRevokesToken() {
        JwtProvider.RefreshTokenResult rt = jwtProvider.generateRefreshToken(42L);

        authService.logout(rt.token());

        verify(refreshTokenMapper).revokeByJti(rt.jti());
    }

    @Test
    void logoutInvalidTokenNoOp() {
        authService.logout("garbage");
        verify(refreshTokenMapper, never()).revokeByJti(any());
    }

    @Test
    void changePasswordRevokesAllTokens() {
        User user = new User("alice", "alice@test.com", PasswordHasher.hash("oldPw"));
        user.setId(42L);
        when(userMapper.findById(42L)).thenReturn(user);
        when(userMapper.updatePassword(eq(42L), anyString())).thenReturn(1);

        authService.changePassword(42L, "oldPw", "newPw123");

        verify(userMapper).updatePassword(eq(42L), anyString());
        verify(refreshTokenMapper).revokeAllByUserId(42L);
    }

    @Test
    void changePasswordWrongOldThrows() {
        User user = new User("alice", "alice@test.com", PasswordHasher.hash("correct"));
        user.setId(42L);
        when(userMapper.findById(42L)).thenReturn(user);

        assertThrows(InvalidCredentialsException.class, () -> authService.changePassword(42L, "wrong", "newPw"));
    }
}
