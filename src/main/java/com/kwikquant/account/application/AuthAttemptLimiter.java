package com.kwikquant.account.application;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.kwikquant.account.domain.AccountDisabledException;
import com.kwikquant.account.domain.AuthRateLimitExceededException;
import com.kwikquant.account.domain.InvalidCredentialsException;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AuthAttemptLimiter {

    private final int maxRequestsPerIp;
    private final int maxFailuresPerAccount;
    private final Semaphore concurrentAttempts;
    private final Cache<String, AtomicInteger> requestsByIp = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(1))
            .maximumSize(100_000)
            .build();
    private final Cache<String, AtomicInteger> failuresByAccount = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(15))
            .maximumSize(100_000)
            .build();

    public AuthAttemptLimiter(
            @Value("${kwikquant.auth-rate-limit.requests-per-ip-per-minute:30}") int maxRequestsPerIp,
            @Value("${kwikquant.auth-rate-limit.failures-per-account:10}") int maxFailuresPerAccount,
            @Value("${kwikquant.auth-rate-limit.max-concurrent:4}") int maxConcurrent) {
        this.maxRequestsPerIp = maxRequestsPerIp;
        this.maxFailuresPerAccount = maxFailuresPerAccount;
        this.concurrentAttempts = new Semaphore(maxConcurrent);
    }

    public <T> T execute(String clientIp, String accountKey, Supplier<T> attempt) {
        String ip = clientIp == null || clientIp.isBlank() ? "unknown" : clientIp;
        String account = accountKey == null ? "" : accountKey.trim().toLowerCase(Locale.ROOT);
        if (increment(requestsByIp, ip) > maxRequestsPerIp
                || count(failuresByAccount, account) >= maxFailuresPerAccount) {
            throw new AuthRateLimitExceededException();
        }
        if (!concurrentAttempts.tryAcquire()) {
            throw new AuthRateLimitExceededException();
        }
        try {
            T result = attempt.get();
            failuresByAccount.invalidate(account);
            return result;
        } catch (InvalidCredentialsException | AccountDisabledException e) {
            increment(failuresByAccount, account);
            throw e;
        } finally {
            concurrentAttempts.release();
        }
    }

    private static int increment(Cache<String, AtomicInteger> cache, String key) {
        return cache.get(key, ignored -> new AtomicInteger()).incrementAndGet();
    }

    private static int count(Cache<String, AtomicInteger> cache, String key) {
        AtomicInteger count = cache.getIfPresent(key);
        return count == null ? 0 : count.get();
    }
}
