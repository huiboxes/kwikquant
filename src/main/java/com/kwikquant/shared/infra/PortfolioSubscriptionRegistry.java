package com.kwikquant.shared.infra;

import java.util.Set;

/**
 * Tracks which users currently hold an active STOMP subscription to
 * {@code /topic/portfolio/{userId}}, so {@code PortfolioService.scheduledPush} can iterate only
 * connected users instead of all accounts (avoiding pointless remote balance fetches).
 *
 * <p>Placed in {@code shared.infra} on purpose: {@code StompSubscriptionInterceptor}
 * (market.infrastructure) and {@code PortfolioService} (report.application) both depend on
 * {@code shared.infra}, satisfying Spring Modulith boundaries — {@code market} must not depend on
 * {@code report}, so the registry cannot live in either; {@code shared} is the only legal common
 * owner.
 *
 * <p>Lifecycle hooks mirror STOMP frames:
 *
 * <ul>
 *   <li>{@link #register} on SUBSCRIBE {@code /topic/portfolio/{userId}} (after the auth gate)
 *   <li>{@link #unregister} on UNSUBSCRIBE (subId-only, destination reverse-looked-up internally)
 *   <li>{@link #clearSession} on session disconnect (covers client close / network drop / runner
 *       SIGKILL → broker probe fail)
 * </ul>
 */
public interface PortfolioSubscriptionRegistry {

    void register(String sessionId, String subscriptionId, long userId);

    void unregister(String sessionId, String subscriptionId);

    void clearSession(String sessionId);

    /** Snapshot of distinct userIds with at least one active portfolio subscription. */
    Set<Long> activeUserIds();
}
