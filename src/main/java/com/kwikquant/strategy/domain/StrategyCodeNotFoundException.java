package com.kwikquant.strategy.domain;

import com.kwikquant.shared.infra.ResourceNotFoundException;

public class StrategyCodeNotFoundException extends ResourceNotFoundException {
    private final long strategyCodeId;

    public StrategyCodeNotFoundException(long strategyCodeId) {
        super("StrategyCode", strategyCodeId);
        this.strategyCodeId = strategyCodeId;
    }

    public long strategyCodeId() {
        return strategyCodeId;
    }
}
