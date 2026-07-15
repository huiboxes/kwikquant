package com.kwikquant.strategy.infrastructure;

import com.kwikquant.strategy.application.SubprocessExecutor;
import com.kwikquant.strategy.application.SubprocessResult;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * {@link SubprocessExecutor} 默认实现:ProcessBuilder + 异步读 stdout/stderr + waitFor(timeout)。
 *
 * <p>stdout/stderr 必须在 waitFor 之前异步读取——如果子进程 stdout 超过 OS 管道缓冲区
 * (通常 64KB)，子进程写 stdout 阻塞 → waitFor 等不到退出 → 超时 → destroyForcibly。
 * 包含数千笔交易的回测 §8 JSON 很容易超过 64KB。
 *
 * <p>JaCoCo 排除(subprocess 启动不可单测,PSR 逻辑通过 mock SubprocessExecutor 覆盖)。
 */
@Component
public class RealSubprocessExecutor implements SubprocessExecutor {

    /** reader 线程 join 超时（毫秒），子进程已被 destroy 后等待 reader 线程收尾的上限。 */
    private static final long READER_JOIN_TIMEOUT_MS = 5000;

    @Override
    public SubprocessResult run(List<String> command, Map<String, String> env, long timeoutSec) {
        ProcessBuilder pb = new ProcessBuilder(command);
        if (env != null) {
            pb.environment().putAll(env);
        }
        pb.redirectErrorStream(false);
        try {
            Process process = pb.start();
            // 异步读 stdout/stderr 防止管道缓冲区满导致死锁
            StringBuilder stdoutBuf = new StringBuilder();
            StringBuilder stderrBuf = new StringBuilder();
            Thread stdoutReader = Thread.ofVirtual().start(() -> drainStream(process.getInputStream(), stdoutBuf));
            Thread stderrReader = Thread.ofVirtual().start(() -> drainStream(process.getErrorStream(), stderrBuf));
            boolean finished = process.waitFor(timeoutSec, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                joinQuietly(stdoutReader);
                joinQuietly(stderrReader);
                return new SubprocessResult(-1, stdoutBuf.toString(), stderrBuf.toString(), true);
            }
            joinQuietly(stdoutReader);
            joinQuietly(stderrReader);
            return new SubprocessResult(process.exitValue(), stdoutBuf.toString(), stderrBuf.toString(), false);
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            return new SubprocessResult(-1, "", "spawn failed: " + e.getMessage(), false);
        }
    }

    private static void drainStream(InputStream is, StringBuilder buf) {
        if (is == null) return;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!buf.isEmpty()) buf.append('\n');
                buf.append(line);
            }
        } catch (IOException ignored) {
            // 子进程被 destroyForcibly 时流会被关闭,此处 IOException 是正常的
        }
    }

    private static void joinQuietly(Thread t) {
        try {
            t.join(READER_JOIN_TIMEOUT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
