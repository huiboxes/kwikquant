package com.kwikquant.strategy.domain;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Strategy code version lifecycle status.
 *
 * <pre>
 * DRAFT -> PUBLISHED
 * PUBLISHED -> ARCHIVED
 * </pre>
 */
public enum StrategyCodeStatus {
    DRAFT,
    PUBLISHED,
    ARCHIVED;

    private static final Map<StrategyCodeStatus, Set<StrategyCodeStatus>> ALLOWED = Map.of(
            DRAFT, EnumSet.of(PUBLISHED),
            PUBLISHED, EnumSet.of(ARCHIVED),
            ARCHIVED, EnumSet.noneOf(StrategyCodeStatus.class));

    public Set<StrategyCodeStatus> allowedTransitions() {
        return ALLOWED.getOrDefault(this, Set.of());
    }

    public boolean canTransitionTo(StrategyCodeStatus target) {
        return allowedTransitions().contains(target);
    }
}
