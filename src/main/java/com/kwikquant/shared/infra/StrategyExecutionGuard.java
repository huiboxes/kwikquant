package com.kwikquant.shared.infra;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/** 串行化同一策略的生命周期写操作（start/pause/stop/markError）与 worker 下单，封闭生命周期/下单 TOCTOU 窗口。 */
@Component
public class StrategyExecutionGuard {

    private final ConcurrentHashMap<Long, ReentrantReadWriteLock> locks = new ConcurrentHashMap<>();

    public <T> T submit(long strategyId, Supplier<T> action) {
        var lock = lockFor(strategyId).readLock();
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    public <T> T transition(long strategyId, Supplier<T> action) {
        var lock = lockFor(strategyId).writeLock();
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    private ReentrantReadWriteLock lockFor(long strategyId) {
        return locks.computeIfAbsent(strategyId, ignored -> new ReentrantReadWriteLock(true));
    }
}
