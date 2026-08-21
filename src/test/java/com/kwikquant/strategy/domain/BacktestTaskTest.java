package com.kwikquant.strategy.domain;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class BacktestTaskTest {

    @Test
    void create_setsPendingAndDefaultParameters() {
        BacktestTask t = BacktestTask.create(
                1L, 42L, 5L, "BTC/USDT", "BINANCE", "SPOT", "1h", Instant.now(), Instant.now(), null);
        assertEquals(BacktestTaskStatus.PENDING, t.getStatus());
        assertEquals("{}", t.getParameters());
        assertEquals(5L, t.getStrategyCodeId());
        assertEquals("SPOT", t.getMarketType());
    }

    @Test
    void transitionTo_pendingToRunningToCompleted() {
        BacktestTask t = BacktestTask.create(
                1L, 42L, 5L, "BTC/USDT", "BINANCE", "SPOT", "1h", Instant.now(), Instant.now(), "{}");
        t.transitionTo(BacktestTaskStatus.RUNNING);
        t.transitionTo(BacktestTaskStatus.COMPLETED);
        assertTrue(t.getStatus().isTerminal());
    }

    @Test
    void transitionTo_runningToFailed() {
        BacktestTask t = BacktestTask.create(
                1L, 42L, 5L, "BTC/USDT", "BINANCE", "SPOT", "1h", Instant.now(), Instant.now(), "{}");
        t.transitionTo(BacktestTaskStatus.RUNNING);
        t.transitionTo(BacktestTaskStatus.FAILED);
        assertEquals(BacktestTaskStatus.FAILED, t.getStatus());
    }

    @Test
    void transitionTo_pendingToCompletedIllegal() {
        BacktestTask t = BacktestTask.create(
                1L, 42L, 5L, "BTC/USDT", "BINANCE", "SPOT", "1h", Instant.now(), Instant.now(), "{}");
        // PENDING → COMPLETED 非法（必须先 RUNNING）
        assertThrows(
                IllegalBacktestTaskStateTransitionException.class, () -> t.transitionTo(BacktestTaskStatus.COMPLETED));
    }

    @Test
    void transitionTo_completedToRunningIllegal() {
        BacktestTask t = BacktestTask.create(
                1L, 42L, 5L, "BTC/USDT", "BINANCE", "SPOT", "1h", Instant.now(), Instant.now(), "{}");
        t.transitionTo(BacktestTaskStatus.RUNNING);
        t.transitionTo(BacktestTaskStatus.COMPLETED);
        assertThrows(
                IllegalBacktestTaskStateTransitionException.class, () -> t.transitionTo(BacktestTaskStatus.RUNNING));
    }

    @Test
    void progressBars_getterSetter() {
        // worker 逐 bar 上报字段;create 不设(默认 null),setter/getter 往返
        BacktestTask t = BacktestTask.create(
                1L, 42L, 5L, "BTC/USDT", "BINANCE", "SPOT", "1h", Instant.now(), Instant.now(), "{}");
        assertNull(t.getProcessedBars());
        assertNull(t.getTotalBars());
        t.setProcessedBars(4400);
        t.setTotalBars(8760);
        assertEquals(4400, t.getProcessedBars());
        assertEquals(8760, t.getTotalBars());
    }
}
