package com.kwikquant.strategy.application;

import com.kwikquant.shared.types.StrategyRunStateChecker;
import com.kwikquant.shared.types.StrategyStatus;
import com.kwikquant.strategy.domain.StrategyDefinition;
import com.kwikquant.strategy.infrastructure.StrategyMapper;
import org.springframework.stereotype.Component;

/** 由 strategy 模块实现 shared 只读端口，避免 trading 反向依赖 strategy。 */
@Component
class StrategyRunStateCheckerAdapter implements StrategyRunStateChecker {

    private final StrategyMapper strategyMapper;

    StrategyRunStateCheckerAdapter(StrategyMapper strategyMapper) {
        this.strategyMapper = strategyMapper;
    }

    @Override
    public boolean isRunning(long strategyId) {
        StrategyDefinition strategy = strategyMapper.findById(strategyId);
        return strategy != null && strategy.getStatus() == StrategyStatus.RUNNING;
    }
}
