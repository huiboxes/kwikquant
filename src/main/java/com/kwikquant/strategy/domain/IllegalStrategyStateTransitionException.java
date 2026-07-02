package com.kwikquant.strategy.domain;

import com.kwikquant.shared.types.StrategyStatus;

public class IllegalStrategyStateTransitionException extends RuntimeException {
    private final StrategyStatus from;
    private final StrategyStatus to;

    public IllegalStrategyStateTransitionException(StrategyStatus from, StrategyStatus to) {
        super("Illegal strategy state transition: " + from + " -> " + to);
        this.from = from;
        this.to = to;
    }

    public StrategyStatus from() {
        return from;
    }

    public StrategyStatus to() {
        return to;
    }
}
