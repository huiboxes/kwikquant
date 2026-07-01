package com.kwikquant.shared.infra;

import static org.assertj.core.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class AuditExecutorTest {

    @Test
    void submit_executesTask() throws InterruptedException {
        AuditExecutor executor = new AuditExecutor(1, 2, 10, 60);
        AtomicBoolean executed = new AtomicBoolean(false);
        CountDownLatch latch = new CountDownLatch(1);

        executor.submit(() -> {
            executed.set(true);
            latch.countDown();
        });

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(executed.get()).isTrue();
        executor.shutdown();
    }

    @Test
    void shutdown_completesGracefully() {
        AuditExecutor executor = new AuditExecutor(1, 2, 10, 60);
        executor.submit(() -> {});
        // Should not throw or hang
        assertThatCode(() -> executor.shutdown()).doesNotThrowAnyException();
    }

    @Test
    void shutdown_whenEmpty_completesImmediately() {
        AuditExecutor executor = new AuditExecutor(1, 2, 10, 60);
        assertThatCode(() -> executor.shutdown()).doesNotThrowAnyException();
    }
}
