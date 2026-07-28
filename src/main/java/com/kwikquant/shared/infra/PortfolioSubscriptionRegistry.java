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
 *
 * <p><b>Implementation contract</b>: implementations must be thread-safe —
 * {@code StompSubscriptionInterceptor} calls {@code register}/{@code unregister} from the
 * clientInboundChannel thread while {@code PortfolioService.scheduledPush} reads
 * {@link #activeUserIds} from the scheduler thread, concurrently.
 *
 * <p><b>Multi-instance semantics</b>: with {@code enableSimpleBroker} (in-process broker) +
 * per-instance {@code @Scheduled} {@code scheduledPush}, each instance pushes only its own
 * registry's userIds to its own broker; a client session reaches the broker on the instance it
 * connected to, so multi-instance still works as long as session and broker are co-located (WS
 * reconnect lands on a new instance and re-SUBSCRIBE re-registers). What this in-process registry
 * does <em>not</em> support is <b>cross-instance push</b> — a session on instance A being pushed
 * from an event firing on instance B. That scenario requires a shared broker (Redis pub/sub
 * relay) and a shared-state registry; out of scope until cross-instance push is needed.
 */
public interface PortfolioSubscriptionRegistry {

    void register(String sessionId, String subscriptionId, long userId);

    void unregister(String sessionId, String subscriptionId);

    void clearSession(String sessionId);

    /** Snapshot of distinct userIds with at least one active portfolio subscription. */
    Set<Long> activeUserIds();
}
