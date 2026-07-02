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
 * {@link SubprocessExecutor} 默认实现:ProcessBuilder + waitFor(timeout) + destroyForcibly。
 *
 * <p>JaCoCo 排除(subprocess 启动不可单测,PSR 逻辑通过 mock SubprocessExecutor 覆盖)。
 * 读 stdout/stderr 在 waitFor 后(小输出 <64KB 不死锁);大输出需 redirectErrorStream 或异步读,Wave 8 回测 §8 JSON 小,够用。
 */
@Component
public class RealSubprocessExecutor implements SubprocessExecutor {

    @Override
    public SubprocessResult run(List<String> command, Map<String, String> env, long timeoutSec) {
        ProcessBuilder pb = new ProcessBuilder(command);
        if (env != null) {
            pb.environment().putAll(env);
        }
        pb.redirectErrorStream(false);
        try {
            Process process = pb.start();
            boolean finished = process.waitFor(timeoutSec, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                String stderr = readStream(process.getErrorStream());
                return new SubprocessResult(-1, "", stderr, true);
            }
            String stdout = readStream(process.getInputStream());
            String stderr = readStream(process.getErrorStream());
            return new SubprocessResult(process.exitValue(), stdout, stderr, false);
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            return new SubprocessResult(-1, "", "spawn failed: " + e.getMessage(), false);
        }
    }

    private static String readStream(InputStream is) throws IOException {
        if (is == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(line);
            }
        }
        return sb.toString();
    }
}
