package com.kwikquant.strategy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.kwikquant.shared.types.StrategyStatus;
import com.kwikquant.strategy.domain.StrategyDefinition;
import com.kwikquant.strategy.infrastructure.StrategyMapper;
import org.junit.jupiter.api.Test;

class StrategyRunStateCheckerAdapterTest {

    private final StrategyMapper strategyMapper = mock(StrategyMapper.class);
    private final StrategyRunStateCheckerAdapter checker = new StrategyRunStateCheckerAdapter(strategyMapper);

    @Test
    void isRunning_onlyAcceptsExistingRunningStrategy() {
        StrategyDefinition running = new StrategyDefinition();
        running.setStatus(StrategyStatus.RUNNING);
        StrategyDefinition paused = new StrategyDefinition();
        paused.setStatus(StrategyStatus.PAUSED);
        when(strategyMapper.findById(1L)).thenReturn(running);
        when(strategyMapper.findById(2L)).thenReturn(paused);
        when(strategyMapper.findById(3L)).thenReturn(null);

        assertThat(checker.isRunning(1L)).isTrue();
        assertThat(checker.isRunning(2L)).isFalse();
        assertThat(checker.isRunning(3L)).isFalse();
    }
}
