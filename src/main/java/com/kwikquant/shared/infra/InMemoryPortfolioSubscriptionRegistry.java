package com.kwikquant.shared.infra;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * In-process {@link PortfolioSubscriptionRegistry}.
 *
 * <p>State: {@code sessionId → (subscriptionId → userId)}. Mirrors
 * {@code StompSubscriptionInterceptor.sessionSubscriptions} shape deliberately — same data
 * structure pattern, no new paradigm. {@link #activeUserIds()} dedupes via {@code toSet()}: a userId
 * with N sessions stays active until the last session unsubscribes/disconnects (natural refcount
 * semantics, no explicit counter).
 *
 * <p><b>Single-instance assumption</b>: pairs with {@code enableSimpleBroker("/topic")} (single
 * in-process broker). Multi-instance deployment with a shared broker would need a shared
 * (Redis-backed) registry; out of scope for the current topology.
 */
@Component
public class InMemoryPortfolioSubscriptionRegistry implements PortfolioSubscriptionRegistry {

    private final ConcurrentMap<String, ConcurrentMap<String, Long>> sessions = new ConcurrentHashMap<>();

    @Override
    public void register(String sessionId, String subscriptionId, long userId) {
        if (sessionId == null || subscriptionId == null) {
            return;
        }
        sessions.computeIfAbsent(sessionId, key -> new ConcurrentHashMap<>()).put(subscriptionId, userId);
    }

    @Override
    public void unregister(String sessionId, String subscriptionId) {
        if (sessionId == null || subscriptionId == null) {
            return;
        }
        ConcurrentMap<String, Long> subs = sessions.get(sessionId);
        if (subs == null) {
            return;
        }
        subs.remove(subscriptionId);
        if (subs.isEmpty()) {
            // Benign TOCTOU: two threads removing the last two subIds may both see empty and both
            // call remove — idempotent. A concurrent register re-creating the inner map is also
            // fine (computeIfAbsent). Worst case: a stale empty inner map lingers, which does not
            // affect activeUserIds() correctness.
            sessions.remove(sessionId);
        }
    }

    @Override
    public void clearSession(String sessionId) {
        if (sessionId == null) {
            return;
        }
        sessions.remove(sessionId);
    }

    @Override
    public Set<Long> activeUserIds() {
        return sessions.values().stream()
                .flatMap(inner -> inner.values().stream())
                .collect(Collectors.toSet());
    }
}
