package com.kwikquant.shared.infra;

import static org.assertj.core.api.Assertions.*;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Round 4 补：验证 {@link AsyncConfig} 的 MDC 传播 —— 这是 traceId 跨 @Async 边界不断链的关键机制，
 * 若未来 refactor 破坏 TaskDecorator，异步执行日志无法关联回原请求（Round 2 M3 修复项）。
 */
class AsyncConfigTest {

    private Executor executor;

    @BeforeEach
    void setUp() {
        executor = new AsyncConfig().taskExecutor(2, 8, 50);
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
        if (executor instanceof ThreadPoolTaskExecutor tpe) {
            tpe.shutdown();
        }
    }

    @Test
    void parentMdc_isPropagatedToAsyncThread() throws Exception {
        MDC.put(MdcKeys.TRACE_ID, "T-42");
        CompletableFuture<String> got = new CompletableFuture<>();
        executor.execute(() -> got.complete(MDC.get(MdcKeys.TRACE_ID)));

        assertThat(got.get(5, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo("T-42");
    }

    @Test
    void emptyParentMdc_leavesAsyncThreadClean() throws Exception {
        MDC.clear();
        CompletableFuture<String> got = new CompletableFuture<>();
        executor.execute(() -> got.complete(MDC.get(MdcKeys.TRACE_ID)));

        assertThat(got.get(5, java.util.concurrent.TimeUnit.SECONDS)).isNull();
    }

    @Test
    void asyncThread_mdcRestoredAfterRun() throws Exception {
        // Round 4 契约：异步执行完成后线程池归还线程时，MDC 不应残留（避免线程复用泄漏）。
        MDC.put(MdcKeys.TRACE_ID, "T-first");
        CompletableFuture<Void> first = CompletableFuture.runAsync(
                () -> {
                    /* 只是占坑触发线程创建 */
                },
                executor);
        first.get(5, java.util.concurrent.TimeUnit.SECONDS);

        // 之后一个"父线程空 MDC"的任务，应该读到 null（不复用第一个任务残留的 "T-first"）
        MDC.clear();
        CompletableFuture<String> got = new CompletableFuture<>();
        executor.execute(() -> got.complete(MDC.get(MdcKeys.TRACE_ID)));
        assertThat(got.get(5, java.util.concurrent.TimeUnit.SECONDS)).isNull();
    }
}
