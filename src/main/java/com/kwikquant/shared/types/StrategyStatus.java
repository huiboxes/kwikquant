package com.kwikquant.shared.types;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Strategy lifecycle status with state-machine transitions.
 *
 * <p>Located in {@code shared/types} (not {@code strategy/domain}) so that
 * {@link StrategyStatusChangedEvent} (also in shared/types) can reference it without a reverse
 * dependency from shared → strategy. Mirrors the {@link OrderStatus} precedent.
 *
 * <pre>
 * DRAFT -> READY
 * READY -> RUNNING, DRAFT
 * RUNNING -> PAUSED, STOPPED, ERROR
 * PAUSED -> RUNNING, STOPPED
 * ERROR -> STOPPED
 * STOPPED -> DRAFT
 * </pre>
 */
public enum StrategyStatus {
    DRAFT,
    READY,
    RUNNING,
    PAUSED,
    STOPPED,
    ERROR;

    private static final Map<StrategyStatus, Set<StrategyStatus>> ALLOWED = Map.of(
            DRAFT, EnumSet.of(READY),
            READY, EnumSet.of(RUNNING, DRAFT),
            RUNNING, EnumSet.of(PAUSED, STOPPED, ERROR),
            PAUSED, EnumSet.of(RUNNING, STOPPED),
            ERROR, EnumSet.of(STOPPED),
            STOPPED, EnumSet.of(DRAFT));

    public Set<StrategyStatus> allowedTransitions() {
        return ALLOWED.getOrDefault(this, Set.of());
    }

    public boolean canTransitionTo(StrategyStatus target) {
        return allowedTransitions().contains(target);
    }

    public boolean isTerminal() {
        return this == STOPPED;
    }
}
