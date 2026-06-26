package com.kwikquant.account.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.kwikquant.account.domain.InvalidCredentialsException;
import com.kwikquant.account.domain.PasswordHasher;
import com.kwikquant.account.domain.User;
import com.kwikquant.account.infrastructure.JwtProvider;
import com.kwikquant.account.infrastructure.RefreshTokenMapper;
import com.kwikquant.account.infrastructure.RefreshTokenMapper.RefreshTokenRow;
import com.kwikquant.account.infrastructure.UserMapper;
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
    private AuthService authService;

    @BeforeEach
    void setUp() {
        userMapper = mock(UserMapper.class);
        refreshTokenMapper = mock(RefreshTokenMapper.class);
        SecretKey key = Jwts.SIG.HS256.key().build();
        jwtProvider = new JwtProvider(key, Duration.ofMinutes(15), Duration.ofDays(7));
        authService = new AuthService(userMapper, refreshTokenMapper, jwtProvider);
    }

    @Test
    void registerSuccess() {
        when(userMapper.findByUsername("alice")).thenReturn(null);
        when(userMapper.findByEmail("alice@test.com")).thenReturn(null);
        doAnswer(inv -> {
                    User u = inv.getArgument(0);
                    u.setId(1L);
                    return null;
                })
                .when(userMapper)
                .insert(any(User.class));

        AuthService.AuthResult result = authService.register("alice", "alice@test.com", "password123");

        assertNotNull(result.accessToken());
        assertNotNull(result.refreshToken());
        assertTrue(result.expiresIn() > 0);
        verify(userMapper).insert(any(User.class));
        verify(refreshTokenMapper).insert(any(RefreshTokenRow.class));
    }

    @Test
    void registerDuplicateUsernameThrows() {
        when(userMapper.findByUsername("alice")).thenReturn(new User());

        assertThrows(
                IllegalArgumentException.class, () -> authService.register("alice", "alice@test.com", "password123"));
    }

    @Test
    void registerDuplicateEmailThrows() {
        when(userMapper.findByUsername("alice")).thenReturn(null);
        when(userMapper.findByEmail("alice@test.com")).thenReturn(new User());

        assertThrows(
                IllegalArgumentException.class, () -> authService.register("alice", "alice@test.com", "password123"));
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
