package com.kwikquant.shared.infra;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class StrategyExecutionGuardTest {

    @Test
    void transition_waitsForInFlightSubmit() throws Exception {
        StrategyExecutionGuard guard = new StrategyExecutionGuard();
        CountDownLatch submitEntered = new CountDownLatch(1);
        CountDownLatch releaseSubmit = new CountDownLatch(1);
        CountDownLatch transitionEntered = new CountDownLatch(1);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var submit = executor.submit(() -> guard.submit(7L, () -> {
                submitEntered.countDown();
                await(releaseSubmit);
                return "submitted";
            }));
            assertThat(submitEntered.await(1, TimeUnit.SECONDS)).isTrue();

            var transition = executor.submit(() -> guard.transition(7L, () -> {
                transitionEntered.countDown();
                return "paused";
            }));
            assertThat(transitionEntered.await(100, TimeUnit.MILLISECONDS)).isFalse();

            releaseSubmit.countDown();
            assertThat(submit.get(1, TimeUnit.SECONDS)).isEqualTo("submitted");
            assertThat(transition.get(1, TimeUnit.SECONDS)).isEqualTo("paused");
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
