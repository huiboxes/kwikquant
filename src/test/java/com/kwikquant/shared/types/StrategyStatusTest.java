package com.kwikquant.shared.types;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class StrategyStatusTest {

    @Test
    void stopped_canTransitionTo_running() {
        assertTrue(StrategyStatus.STOPPED.canTransitionTo(StrategyStatus.RUNNING));
    }

    @Test
    void stopped_cannotTransitionTo_draft() {
        // STOPPED→DRAFT 已从 ALLOWED 移除（避免"声明不实现"的债：service 无 toDraft 方法）
        assertFalse(StrategyStatus.STOPPED.canTransitionTo(StrategyStatus.DRAFT));
    }
}
