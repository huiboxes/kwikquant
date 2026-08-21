package com.kwikquant.shared.infra;

import java.util.Map;
import java.util.concurrent.Executor;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 全局 {@code @Async} 执行器配置，主要目的是把父线程的 MDC 上下文（含 {@code traceId}）
 * 传播到异步执行线程，避免 GlobalExceptionHandler/ApiResponse/Auditable 拿不到 traceId 断链。
 *
 * <p>{@code @Async} 上下文传播：SimpleAsyncTaskExecutor 默认不 copy MDC → 用户 POST /backtests 后
 * 异步失败日志无法关联回原请求,故本配置显式 copy MDC。
 *
 * <p>暴露两个池,不 implements {@link org.springframework.scheduling.annotation.AsyncConfigurer}
 * —— 后者仅允许全项目一个，会与测试里的 {@code SyncAsyncConfig}（把 @Async 同步化）冲突。
 *
 * <ul>
 *   <li>{@code taskExecutor}：通知/动态流等短任务（裸 {@code @Async} 按 bean name 找它）；
 *   <li>{@code backtestExecutor}：回测专用（{@code @Async("backtestExecutor")}）。回测单任务最长
 *       timeout-sec(默认 3600s)且受配额限 per-user 2 / 池 max 4，独立池防长跑回测饿死通知线程
 *       （原共享池 core2/max8,8 个长跑即占满,通知进队列被 AbortPolicy 拒绝）。
 * </ul>
 */
@Configuration
public class AsyncConfig {

    @Bean("taskExecutor")
    public Executor taskExecutor(
            @Value("${kwikquant.async.core-pool-size:2}") int corePoolSize,
            @Value("${kwikquant.async.max-pool-size:8}") int maxPoolSize,
            @Value("${kwikquant.async.queue-capacity:50}") int queueCapacity) {
        // 保守配置：单节点开发/单用户场景。避免打爆 HikariCP 池（默认 15）。
        return newExecutor(corePoolSize, maxPoolSize, queueCapacity, "kwikquant-async-");
    }

    @Bean("backtestExecutor")
    public Executor backtestExecutor(
            @Value("${kwikquant.async.backtest.core-pool-size:2}") int corePoolSize,
            @Value("${kwikquant.async.backtest.max-pool-size:4}") int maxPoolSize,
            @Value("${kwikquant.async.backtest.queue-capacity:16}") int queueCapacity) {
        return newExecutor(corePoolSize, maxPoolSize, queueCapacity, "kwikquant-backtest-");
    }

    private static Executor newExecutor(int corePoolSize, int maxPoolSize, int queueCapacity, String threadPrefix) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix(threadPrefix);
        executor.setTaskDecorator(mdcContextPropagatingDecorator());
        executor.initialize();
        return executor;
    }

    /**
     * TaskDecorator：把父线程当前 MDC 快照 copy 到异步线程，任务结束时清理，避免线程复用污染。
     */
    private static TaskDecorator mdcContextPropagatingDecorator() {
        return runnable -> {
            Map<String, String> parentContext = MDC.getCopyOfContextMap();
            return () -> {
                Map<String, String> previous = MDC.getCopyOfContextMap();
                try {
                    if (parentContext != null) {
                        MDC.setContextMap(parentContext);
                    } else {
                        MDC.clear();
                    }
                    runnable.run();
                } finally {
                    if (previous != null) {
                        MDC.setContextMap(previous);
                    } else {
                        MDC.clear();
                    }
                }
            };
        };
    }
}
