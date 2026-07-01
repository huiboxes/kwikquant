package com.kwikquant.account.interfaces;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.kwikquant.account.application.AuthService;
import com.kwikquant.account.application.AuthService.AuthResult;
import com.kwikquant.account.domain.InvalidCredentialsException;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Unit tests for {@link AuthController}.
 *
 * <p>Pure Mockito style (consistent with ControllerAuthTest / RiskPolicyControllerTest).
 * Covers all public endpoints: register, login, refresh, logout, changePassword.
 */
class AuthControllerTest {

    private AuthService authService;
    private AuthController controller;
    private HttpServletResponse response;

    @BeforeEach
    void setUp() {
        authService = mock(AuthService.class);
        controller = new AuthController(authService);
        response = mock(HttpServletResponse.class);

        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("42", "x"));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ---- register ----

    @Test
    void register_whenValid_returnsTokensAndSetsCookie() {
        AuthResult authResult = new AuthResult("access-token-123", "refresh-token-456", 3600L);
        when(authService.register("alice", "alice@example.com", "password123")).thenReturn(authResult);

        var req = new AuthController.RegisterRequest("alice", "alice@example.com", "password123");
        var result = controller.register(req, response);

        assertThat(result.code()).isEqualTo(0);
        assertThat(result.data().accessToken()).isEqualTo("access-token-123");
        assertThat(result.data().expiresIn()).isEqualTo(3600L);
        verify(authService).register("alice", "alice@example.com", "password123");
        // Verify refresh cookie is set
        verify(response).addHeader(eq(HttpHeaders.SET_COOKIE), contains("refresh-token-456"));
    }

    // ---- login ----

    @Test
    void login_whenValidCredentials_returnsTokensAndSetsCookie() {
        AuthResult authResult = new AuthResult("access-abc", "refresh-def", 7200L);
        when(authService.login("bob", "secret123")).thenReturn(authResult);

        var req = new AuthController.LoginRequest("bob", "secret123");
        var result = controller.login(req, response);

        assertThat(result.code()).isEqualTo(0);
        assertThat(result.data().accessToken()).isEqualTo("access-abc");
        assertThat(result.data().expiresIn()).isEqualTo(7200L);
        verify(authService).login("bob", "secret123");
        verify(response).addHeader(eq(HttpHeaders.SET_COOKIE), contains("refresh-def"));
    }

    @Test
    void login_whenInvalidCredentials_throwsException() {
        when(authService.login("bad", "wrong")).thenThrow(new InvalidCredentialsException());

        var req = new AuthController.LoginRequest("bad", "wrong");

        assertThatThrownBy(() -> controller.login(req, response)).isInstanceOf(InvalidCredentialsException.class);
    }

    // ---- refresh ----

    @Test
    void refresh_whenValidRefreshToken_returnsNewAccessToken() {
        AuthResult authResult = new AuthResult("new-access", "new-refresh", 3600L);
        when(authService.refresh("old-refresh-token")).thenReturn(authResult);

        var result = controller.refresh("old-refresh-token", response);

        assertThat(result.code()).isEqualTo(0);
        assertThat(result.data().accessToken()).isEqualTo("new-access");
        verify(authService).refresh("old-refresh-token");
        verify(response).addHeader(eq(HttpHeaders.SET_COOKIE), contains("new-refresh"));
    }

    @Test
    void refresh_whenNullToken_throwsInvalidCredentials() {
        assertThatThrownBy(() -> controller.refresh(null, response)).isInstanceOf(InvalidCredentialsException.class);

        verifyNoInteractions(authService);
    }

    @Test
    void refresh_whenBlankToken_throwsInvalidCredentials() {
        assertThatThrownBy(() -> controller.refresh("   ", response)).isInstanceOf(InvalidCredentialsException.class);

        verifyNoInteractions(authService);
    }

    // ---- logout ----

    @Test
    void logout_withRefreshToken_revokesAndClearsCookie() {
        var result = controller.logout("my-refresh-token", response);

        assertThat(result.code()).isEqualTo(0);
        verify(authService).logout("my-refresh-token");
        // Verify cookie is cleared (maxAge=0)
        verify(response).addHeader(eq(HttpHeaders.SET_COOKIE), contains("Max-Age=0"));
    }

    @Test
    void logout_withoutRefreshToken_onlyClearsCookie() {
        var result = controller.logout(null, response);

        assertThat(result.code()).isEqualTo(0);
        verifyNoInteractions(authService);
        verify(response).addHeader(eq(HttpHeaders.SET_COOKIE), contains("Max-Age=0"));
    }

    // ---- changePassword ----

    @Test
    void changePassword_delegatesToServiceWithCurrentUserId() {
        var req = new AuthController.ChangePasswordRequest("oldPass123", "newPass456!");

        var result = controller.changePassword(req);

        assertThat(result.code()).isEqualTo(0);
        verify(authService).changePassword(42L, "oldPass123", "newPass456!");
    }
}
