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
 * <p><b>Multi-instance semantics</b>: pairs with {@code enableSimpleBroker("/topic")} (in-process
 * broker) + per-instance {@code @Scheduled} {@code scheduledPush}. Each instance pushes only its
 * own registry's userIds to its own broker; a client session reaches the broker on the instance it
 * connected to, so multi-instance deployment still works as long as session and broker are
 * co-located (WS reconnect lands on a new instance and re-SUBSCRIBE re-registers here). What this
 * in-process registry does <em>not</em> support is <b>cross-instance push</b> — a session on
 * instance A being pushed from an event firing on instance B. That scenario requires a shared
 * broker (Redis pub/sub relay) + a shared-state registry; out of scope until cross-instance push
 * is needed. See {@code PortfolioSubscriptionRegistry} contract.
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
