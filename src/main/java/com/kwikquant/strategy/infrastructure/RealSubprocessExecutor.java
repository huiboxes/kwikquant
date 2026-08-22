package com.kwikquant.strategy.infrastructure;

import com.kwikquant.strategy.application.SubprocessExecutor;
import com.kwikquant.strategy.application.SubprocessResult;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
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
 * 包含数千笔交易的回测结果 JSON 很容易超过 64KB。
 *
 * <p>stdout/stderr 各自按 {@link #MAX_STDOUT_BYTES} 截断(超限丢弃后续并置 stdoutTruncated),
 * 防恶意策略以超大输出耗尽 JVM 内存。
 *
 * <p>stdinPayload 非 null 时异步写入子进程 stdin 后关闭(docker run -i 配置下发通道)。
 *
 * <p>JaCoCo 排除(subprocess 启动不可单测,PSR/DockerBacktestRunner 逻辑通过 mock SubprocessExecutor 覆盖)。
 */
@Component
public class RealSubprocessExecutor implements SubprocessExecutor {

    /** reader 线程 join 超时（毫秒），子进程已被 destroy 后等待 reader 线程收尾的上限。 */
    private static final long READER_JOIN_TIMEOUT_MS = 5000;

    /** stdin 写入线程 join 超时（毫秒）。 */
    private static final long WRITER_JOIN_TIMEOUT_MS = 5000;

    /** 子进程 PATH 白名单；/opt/homebrew 为 Apple Silicon Homebrew 的 python3 所在。 */
    private static final String DEFAULT_PATH = "/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin";

    private static final String DEFAULT_LOCALE = "C.UTF-8";

    @Override
    public SubprocessResult run(List<String> command, Map<String, String> env, String stdinPayload, long timeoutSec) {
        ProcessBuilder pb = new ProcessBuilder(command);
        Map<String, String> processEnv = pb.environment();
        processEnv.clear();
        processEnv.put("PATH", DEFAULT_PATH);
        processEnv.put("PYTHONPATH", System.getProperty("user.dir"));
        processEnv.put("LANG", DEFAULT_LOCALE);
        processEnv.put("LC_ALL", DEFAULT_LOCALE);
        if (env != null) {
            processEnv.putAll(env);
        }
        pb.redirectErrorStream(false);
        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            // 注意:此处不能置线程中断标志——否则同线程下一次 subprocess 调用的 waitFor 会立即
            // 抛 InterruptedException,把"本命令不存在"传染成后续所有调用失败(自检与自动搭建
            // 在同一线程连续 spawn,依赖此语义)。
            return new SubprocessResult(-1, "", "spawn failed: " + e.getMessage(), false, false);
        }
        try {
            // stdin 写入(docker run -i 配置下发);写完立即关闭,子进程 read stdin 才能得到 EOF 继续
            Thread stdinWriter = null;
            if (stdinPayload != null) {
                stdinWriter = Thread.ofVirtual().start(() -> writeStdin(process.getOutputStream(), stdinPayload));
            } else {
                process.getOutputStream().close();
            }
            // 异步读 stdout/stderr 防止管道缓冲区满导致死锁;截断上限防超大输出 OOM
            CappedBuffer stdoutBuf = new CappedBuffer(MAX_STDOUT_BYTES);
            CappedBuffer stderrBuf = new CappedBuffer(MAX_STDOUT_BYTES);
            Thread stdoutReader = Thread.ofVirtual().start(() -> drainStream(process.getInputStream(), stdoutBuf));
            Thread stderrReader = Thread.ofVirtual().start(() -> drainStream(process.getErrorStream(), stderrBuf));
            boolean finished = process.waitFor(timeoutSec, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                joinQuietly(stdinWriter, WRITER_JOIN_TIMEOUT_MS);
                joinQuietly(stdoutReader, READER_JOIN_TIMEOUT_MS);
                joinQuietly(stderrReader, READER_JOIN_TIMEOUT_MS);
                return new SubprocessResult(
                        -1, stdoutBuf.toString(), stderrBuf.toString(), true, stdoutBuf.truncated());
            }
            joinQuietly(stdinWriter, WRITER_JOIN_TIMEOUT_MS);
            joinQuietly(stdoutReader, READER_JOIN_TIMEOUT_MS);
            joinQuietly(stderrReader, READER_JOIN_TIMEOUT_MS);
            return new SubprocessResult(
                    process.exitValue(), stdoutBuf.toString(), stderrBuf.toString(), false, stdoutBuf.truncated());
        } catch (InterruptedException e) {
            // 已启动的子进程必须收掉,避免孤儿进程(如建到一半的 venv);中断标志照章恢复
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            return new SubprocessResult(-1, "", "interrupted while waiting for subprocess", false, false);
        } catch (IOException e) {
            // 进程已启动（如 close stdin 时子进程抢跑退出），同样要收掉，与中断分支一致
            process.destroyForcibly();
            return new SubprocessResult(-1, "", "subprocess io failed: " + e.getMessage(), false, false);
        }
    }

    private static void writeStdin(OutputStream stdin, String payload) {
        try {
            stdin.write(payload.getBytes(StandardCharsets.UTF_8));
            stdin.flush();
            stdin.close();
        } catch (IOException ignored) {
            // 子进程提前退出/被 destroy 时管道关闭,写入失败是正常的
        }
    }

    private static void drainStream(InputStream is, CappedBuffer buf) {
        if (is == null) return;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                buf.appendLine(line);
            }
        } catch (IOException ignored) {
            // 子进程被 destroyForcibly 时流会被关闭,此时 IOException 是正常的
        }
    }

    private static void joinQuietly(Thread thread, long timeoutMs) {
        if (thread == null) return;
        try {
            thread.join(timeoutMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 带上限的行缓冲:超过 maxBytes 后丢弃后续行并置 truncated 标记(不做精确字节计数,按行粒度截断)。 */
    static final class CappedBuffer {
        private final StringBuilder sb = new StringBuilder();
        private final long maxBytes;
        private long bytes;
        private boolean truncated;

        CappedBuffer(long maxBytes) {
            this.maxBytes = maxBytes;
        }

        void appendLine(String line) {
            if (truncated) return;
            long lineBytes = line.length() + 1L;
            if (bytes + lineBytes > maxBytes) {
                truncated = true;
                return;
            }
            bytes += lineBytes;
            if (!sb.isEmpty()) sb.append('\n');
            sb.append(line);
        }

        boolean truncated() {
            return truncated;
        }

        @Override
        public String toString() {
            return sb.toString();
        }
    }
}
