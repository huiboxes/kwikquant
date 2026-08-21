package com.kwikquant.account.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kwikquant.account.domain.AuthRateLimitExceededException;
import com.kwikquant.account.domain.InvalidCredentialsException;
import org.junit.jupiter.api.Test;

class AuthAttemptLimiterTest {

    @Test
    void execute_excessiveRequestsFromIp_rejectsBeforeAttempt() {
        AuthAttemptLimiter limiter = new AuthAttemptLimiter(1, 10, 1);
        limiter.execute("192.0.2.1", "login:user", () -> "ok");

        assertThatThrownBy(() -> limiter.execute("192.0.2.1", "login:other", () -> "unexpected"))
                .isInstanceOf(AuthRateLimitExceededException.class);
    }

    @Test
    void execute_repeatedAccountFailures_rejectsBeforeHashingAgain() {
        AuthAttemptLimiter limiter = new AuthAttemptLimiter(100, 1, 1);
        assertThatThrownBy(() -> limiter.execute("192.0.2.1", "login:user", () -> {
                    throw new InvalidCredentialsException();
                }))
                .isInstanceOf(InvalidCredentialsException.class);

        assertThatThrownBy(() -> limiter.execute("192.0.2.2", "LOGIN:USER", () -> "unexpected"))
                .isInstanceOf(AuthRateLimitExceededException.class);
    }
}
