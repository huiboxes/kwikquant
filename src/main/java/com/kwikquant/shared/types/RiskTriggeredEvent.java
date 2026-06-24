package com.kwikquant.shared.types;

import java.time.Instant;
import java.util.Objects;

public record RiskTriggeredEvent(AccountId accountId, StrategyId strategyId, String reason, Instant timestamp) {

    public RiskTriggeredEvent {
        Objects.requireNonNull(accountId);
        Objects.requireNonNull(strategyId);
        Objects.requireNonNull(reason);
        Objects.requireNonNull(timestamp);
    }
}
