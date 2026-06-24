package com.kwikquant.shared.infra;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AuditExecutor {

    private static final Logger log = LoggerFactory.getLogger(AuditExecutor.class);

    private final ThreadPoolExecutor threadPool;

    public AuditExecutor(int corePoolSize, int maxPoolSize, int queueCapacity, long keepAliveSeconds) {
        AtomicInteger counter = new AtomicInteger(1);
        this.threadPool = new ThreadPoolExecutor(
                corePoolSize,
                maxPoolSize,
                keepAliveSeconds,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                r -> {
                    Thread t = new Thread(r, "audit-async-" + counter.getAndIncrement());
                    t.setDaemon(false);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    public void submit(Runnable task) {
        threadPool.execute(task);
    }

    public void shutdown() {
        threadPool.shutdown();
        try {
            if (!threadPool.awaitTermination(5, TimeUnit.SECONDS)) {
                List<Runnable> dropped = threadPool.shutdownNow();
                log.warn("Audit executor shutdown timed out, cancelled {} queued tasks", dropped.size());
            }
        } catch (InterruptedException ex) {
            threadPool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
