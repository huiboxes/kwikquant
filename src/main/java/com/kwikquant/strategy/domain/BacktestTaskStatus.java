package com.kwikquant.strategy.domain;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Backtest task execution lifecycle status.
 *
 * <pre>
 * PENDING -> RUNNING
 * RUNNING -> COMPLETED, FAILED
 * </pre>
 */
public enum BacktestTaskStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED;

    private static final Map<BacktestTaskStatus, Set<BacktestTaskStatus>> ALLOWED = Map.of(
            PENDING, EnumSet.of(RUNNING),
            RUNNING, EnumSet.of(COMPLETED, FAILED),
            COMPLETED, EnumSet.noneOf(BacktestTaskStatus.class),
            FAILED, EnumSet.noneOf(BacktestTaskStatus.class));

    public Set<BacktestTaskStatus> allowedTransitions() {
        return ALLOWED.getOrDefault(this, Set.of());
    }

    public boolean canTransitionTo(BacktestTaskStatus target) {
        return allowedTransitions().contains(target);
    }

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED;
    }
}
