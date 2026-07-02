package com.kwikquant.strategy.domain;

import com.kwikquant.shared.infra.ResourceNotFoundException;

public class StrategyNotFoundException extends ResourceNotFoundException {
    private final long strategyId;

    public StrategyNotFoundException(long strategyId) {
        super("Strategy", strategyId);
        this.strategyId = strategyId;
    }

    public long strategyId() {
        return strategyId;
    }
}
