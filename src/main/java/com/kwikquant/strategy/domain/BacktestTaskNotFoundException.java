package com.kwikquant.strategy.domain;

import com.kwikquant.shared.infra.ResourceNotFoundException;

public class BacktestTaskNotFoundException extends ResourceNotFoundException {
    private final long backtestTaskId;

    public BacktestTaskNotFoundException(long backtestTaskId) {
        super("BacktestTask", backtestTaskId);
        this.backtestTaskId = backtestTaskId;
    }

    public long backtestTaskId() {
        return backtestTaskId;
    }
}
