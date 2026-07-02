package com.kwikquant.strategy.domain;

/**
 * 策略无 PUBLISHED 代码版本时提交回测/启动抛出。映射 {@code STRATEGY_NO_PUBLISHED_CODE}(7006)。
 */
public class NoPublishedStrategyCodeException extends RuntimeException {

    private final long strategyId;

    public NoPublishedStrategyCodeException(long strategyId) {
        super("No published strategy code for strategy " + strategyId);
        this.strategyId = strategyId;
    }

    public long strategyId() {
        return strategyId;
    }
}
