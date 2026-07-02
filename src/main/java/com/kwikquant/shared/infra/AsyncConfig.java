package com.kwikquant.shared.infra;

import java.util.Map;
import java.util.concurrent.Executor;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 全局 {@code @Async} 执行器配置，主要目的是把父线程的 MDC 上下文（含 {@code traceId}）
 * 传播到异步执行线程，避免 GlobalExceptionHandler/ApiResponse/Auditable 拿不到 traceId 断链。
 *
 * <p>Round 2 修复：Round 1 MAJ-10 拆出 BacktestExecutionGateway 让 {@code @Async} 生效后，
 * SimpleAsyncTaskExecutor 默认不 copy MDC → 用户 POST /backtests 后异步失败日志无法关联回原请求。
 *
 * <p>只暴露 {@code taskExecutor} bean（{@link org.springframework.scheduling.annotation.EnableAsync}
 * 会按 bean name 优先找它），不 implements {@link org.springframework.scheduling.annotation.AsyncConfigurer}
 * —— 后者仅允许全项目一个，会与测试里的 {@code SyncAsyncConfig}（把 @Async 同步化）冲突。
 */
@Configuration
public class AsyncConfig {

    @Bean("taskExecutor")
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // 保守配置：单节点开发/单用户场景。回测异步任务不高频，避免打爆 HikariCP 池（默认 15）。
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("kwikquant-async-");
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
