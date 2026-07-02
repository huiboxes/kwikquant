package com.kwikquant.strategy.domain;

/**
 * StrategyCode 状态非法转换异常。
 *
 * <p>与 {@link IllegalStrategyStateTransitionException} 类似，但针对代码版本状态机。
 */
public class IllegalStrategyCodeStateTransitionException extends RuntimeException {
    private final StrategyCodeStatus from;
    private final StrategyCodeStatus to;

    public IllegalStrategyCodeStateTransitionException(StrategyCodeStatus from, StrategyCodeStatus to) {
        super("Illegal strategy code state transition: " + from + " -> " + to);
        this.from = from;
        this.to = to;
    }

    public StrategyCodeStatus from() {
        return from;
    }

    public StrategyCodeStatus to() {
        return to;
    }
}
