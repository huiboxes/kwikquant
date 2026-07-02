package com.kwikquant.strategy.domain;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class BacktestTaskTest {

    @Test
    void create_setsPendingAndDefaultParameters() {
        BacktestTask t =
                BacktestTask.create(1L, 42L, 5L, "BTC/USDT", "BINANCE", "1h", Instant.now(), Instant.now(), null);
        assertEquals(BacktestTaskStatus.PENDING, t.getStatus());
        assertEquals("{}", t.getParameters());
        assertEquals(5L, t.getStrategyCodeId());
    }

    @Test
    void transitionTo_pendingToRunningToCompleted() {
        BacktestTask t =
                BacktestTask.create(1L, 42L, 5L, "BTC/USDT", "BINANCE", "1h", Instant.now(), Instant.now(), "{}");
        t.transitionTo(BacktestTaskStatus.RUNNING);
        t.transitionTo(BacktestTaskStatus.COMPLETED);
        assertTrue(t.getStatus().isTerminal());
    }

    @Test
    void transitionTo_runningToFailed() {
        BacktestTask t =
                BacktestTask.create(1L, 42L, 5L, "BTC/USDT", "BINANCE", "1h", Instant.now(), Instant.now(), "{}");
        t.transitionTo(BacktestTaskStatus.RUNNING);
        t.transitionTo(BacktestTaskStatus.FAILED);
        assertEquals(BacktestTaskStatus.FAILED, t.getStatus());
    }

    @Test
    void transitionTo_pendingToCompletedIllegal() {
        BacktestTask t =
                BacktestTask.create(1L, 42L, 5L, "BTC/USDT", "BINANCE", "1h", Instant.now(), Instant.now(), "{}");
        // PENDING → COMPLETED 非法（必须先 RUNNING）
        assertThrows(
                IllegalBacktestTaskStateTransitionException.class, () -> t.transitionTo(BacktestTaskStatus.COMPLETED));
    }

    @Test
    void transitionTo_completedToRunningIllegal() {
        BacktestTask t =
                BacktestTask.create(1L, 42L, 5L, "BTC/USDT", "BINANCE", "1h", Instant.now(), Instant.now(), "{}");
        t.transitionTo(BacktestTaskStatus.RUNNING);
        t.transitionTo(BacktestTaskStatus.COMPLETED);
        assertThrows(
                IllegalBacktestTaskStateTransitionException.class, () -> t.transitionTo(BacktestTaskStatus.RUNNING));
    }
}
