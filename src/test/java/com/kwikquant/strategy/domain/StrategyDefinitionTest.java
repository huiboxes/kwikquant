package com.kwikquant.strategy.domain;

import static org.junit.jupiter.api.Assertions.*;

import com.kwikquant.shared.types.StrategyStatus;
import org.junit.jupiter.api.Test;

class StrategyDefinitionTest {

    @Test
    void create_setsDraftStatusAndDefaults() {
        StrategyDefinition s = StrategyDefinition.create(1L, "MA", "d", "BTC/USDT", "BINANCE", null, null, null);
        assertEquals(StrategyStatus.DRAFT, s.getStatus());
        assertEquals("SPOT", s.getMarketType());
        assertEquals("1h", s.getIntervalValue());
        assertEquals("{}", s.getParameters());
        assertFalse(s.isDeleted());
    }

    @Test
    void transitionTo_legalPath() {
        StrategyDefinition s = StrategyDefinition.create(1L, "n", null, "BTC/USDT", "BINANCE", "SPOT", "1h", "{}");
        s.transitionTo(StrategyStatus.READY);
        s.transitionTo(StrategyStatus.RUNNING);
        s.transitionTo(StrategyStatus.PAUSED);
        s.transitionTo(StrategyStatus.RUNNING);
        s.transitionTo(StrategyStatus.STOPPED);
        s.transitionTo(StrategyStatus.DRAFT);
        assertEquals(StrategyStatus.DRAFT, s.getStatus());
    }

    @Test
    void transitionTo_errorFromRunning() {
        StrategyDefinition s = StrategyDefinition.create(1L, "n", null, "BTC/USDT", "BINANCE", "SPOT", "1h", "{}");
        s.transitionTo(StrategyStatus.READY);
        s.transitionTo(StrategyStatus.RUNNING);
        s.transitionTo(StrategyStatus.ERROR);
        s.transitionTo(StrategyStatus.STOPPED);
        assertEquals(StrategyStatus.STOPPED, s.getStatus());
    }

    @Test
    void transitionTo_illegalThrows() {
        StrategyDefinition s = StrategyDefinition.create(1L, "n", null, "BTC/USDT", "BINANCE", "SPOT", "1h", "{}");
        // DRAFT → RUNNING 非法（必须先 READY）
        assertThrows(IllegalStrategyStateTransitionException.class, () -> s.transitionTo(StrategyStatus.RUNNING));
        // DRAFT → STOPPED 非法
        assertThrows(IllegalStrategyStateTransitionException.class, () -> s.transitionTo(StrategyStatus.STOPPED));
    }

    @Test
    void transitionTo_pausedToErrorIllegal() {
        StrategyDefinition s = StrategyDefinition.create(1L, "n", null, "BTC/USDT", "BINANCE", "SPOT", "1h", "{}");
        s.transitionTo(StrategyStatus.READY);
        s.transitionTo(StrategyStatus.RUNNING);
        s.transitionTo(StrategyStatus.PAUSED);
        // PAUSED → ERROR 非法（状态机只允许 PAUSED→RUNNING/STOPPED）
        assertThrows(IllegalStrategyStateTransitionException.class, () -> s.transitionTo(StrategyStatus.ERROR));
    }
}
