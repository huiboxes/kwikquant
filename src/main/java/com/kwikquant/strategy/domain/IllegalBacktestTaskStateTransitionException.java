package com.kwikquant.strategy.domain;

/**
 * BacktestTask 状态非法转换异常。
 */
public class IllegalBacktestTaskStateTransitionException extends RuntimeException {
    private final BacktestTaskStatus from;
    private final BacktestTaskStatus to;

    public IllegalBacktestTaskStateTransitionException(BacktestTaskStatus from, BacktestTaskStatus to) {
        super("Illegal backtest task state transition: " + from + " -> " + to);
        this.from = from;
        this.to = to;
    }

    public BacktestTaskStatus from() {
        return from;
    }

    public BacktestTaskStatus to() {
        return to;
    }
}
