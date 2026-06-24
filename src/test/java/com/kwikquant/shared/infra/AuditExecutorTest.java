package com.kwikquant.shared.infra;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class AuditExecutorTest {

    @Test
    void submitExecutesTask() throws Exception {
        AuditExecutor executor = new AuditExecutor(1, 1, 10, 60);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean ran = new AtomicBoolean(false);

        executor.submit(() -> {
            ran.set(true);
            latch.countDown();
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertTrue(ran.get());
        executor.shutdown();
    }

    @Test
    void shutdownDrainsQueue() throws Exception {
        AuditExecutor executor = new AuditExecutor(1, 1, 100, 60);
        CountDownLatch latch = new CountDownLatch(3);

        for (int i = 0; i < 3; i++) {
            executor.submit(latch::countDown);
        }

        executor.shutdown();
        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }
}
