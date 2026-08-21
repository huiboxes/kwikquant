package com.kwikquant.strategy.application;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 回测 worker(subprocess 模式)启动自检:App ready 时验证 python 解释器可执行 +
 * {@code kwikquant_worker} 包可导入。失败不阻塞启动,但标记 worker 不可用——
 * 提交回测前置拒绝(7305 WORKER_UNAVAILABLE),避免用户等半天才拿到 spawn failed。
 *
 * <p>仅 subprocess runner 生效(dev/test 缺省);prod docker runner 由容器健康探针负责,不加载本 bean。
 * 开关:{@code kwikquant.worker.self-check.enabled}(默认 true)。
 *
 * <p>背景:dev 配置曾硬编码原作者本机 venv 绝对路径,他人机器回测必炸;自检把这类环境债
 * 从"运行时随机炸"前移为"启动即显形 + 提交即拒绝"。
 */
@Component
@ConditionalOnProperty(name = "kwikquant.backtest.runner", havingValue = "subprocess", matchIfMissing = true)
public class BacktestWorkerHealthChecker {

    private static final Logger log = LoggerFactory.getLogger(BacktestWorkerHealthChecker.class);
    private static final long CHECK_TIMEOUT_SEC = 20;

    private final SubprocessExecutor executor;
    private final String pythonCommand;
    private final boolean enabled;

    private volatile boolean available = true;
    private volatile String detail = "未自检";

    public BacktestWorkerHealthChecker(
            SubprocessExecutor executor,
            @Value("${kwikquant.worker.python-command:}") String pythonCommand,
            @Value("${kwikquant.worker.self-check.enabled:true}") boolean enabled) {
        this.executor = executor;
        this.pythonCommand = pythonCommand;
        this.enabled = enabled;
    }

    /** App ready 后自检:解释器 --version + import kwikquant_worker 两步。 */
    @EventListener(ApplicationReadyEvent.class)
    public void selfCheck() {
        if (!enabled) {
            available = true;
            detail = "自检已关闭(kwikquant.worker.self-check.enabled=false)";
            return;
        }
        if (pythonCommand == null || pythonCommand.isBlank()) {
            markUnavailable("kwikquant.worker.python-command 未配置(当前 profile 的 kwikquant.worker 段缺失)");
            return;
        }
        SubprocessResult version = executor.run(List.of(pythonCommand, "--version"), Map.of(), null, CHECK_TIMEOUT_SEC);
        if (version.timedOut() || version.exitCode() != 0) {
            markUnavailable("python 解释器不可执行: " + pythonCommand + " (" + describe(version) + ");"
                    + "修复:export KWIKQUANT_WORKER_PYTHON=<venv>/bin/python 或修正 kwikquant.worker.python-command");
            return;
        }
        SubprocessResult importCheck = executor.run(
                List.of(pythonCommand, "-c", "import kwikquant_worker"), Map.of(), null, CHECK_TIMEOUT_SEC);
        if (importCheck.timedOut() || importCheck.exitCode() != 0) {
            markUnavailable(
                    "kwikquant_worker 依赖不完整: " + describe(importCheck) + ";修复:pip install -r requirements-worker.txt");
            return;
        }
        available = true;
        detail = "ok: " + pythonCommand;
        log.info("backtest worker self-check passed: {}", pythonCommand);
    }

    public boolean isAvailable() {
        return available;
    }

    public String detail() {
        return detail;
    }

    private void markUnavailable(String reason) {
        available = false;
        detail = reason;
        log.error("backtest worker self-check FAILED: {}。提交回测将返 7305。", reason);
    }

    private static String describe(SubprocessResult r) {
        if (r.timedOut()) {
            return "timeout";
        }
        String err = r.stderr() == null ? "" : r.stderr().trim();
        return "exit " + r.exitCode() + (err.isEmpty() ? "" : " " + err);
    }
}
