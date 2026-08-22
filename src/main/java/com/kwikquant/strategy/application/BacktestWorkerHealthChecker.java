package com.kwikquant.strategy.application;

import com.kwikquant.strategy.application.WorkerEnvironmentProvisioner.ProvisionResult;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 回测 worker（subprocess 模式）启动自检 + 环境自愈：App ready 时验证 python 解释器可执行、
 * 运行时依赖可导入；缺失且解释器是路径形态（.venv/bin/python）时，委托
 * {@link WorkerEnvironmentProvisioner} 自动建 venv + 装依赖，装完复探——fresh clone 不用再
 * 手工准备 Python 环境。
 *
 * <p>探测分两步：解释器 {@code --version} + 导入运行时依赖面（{@link #PROBE_IMPORTS}，与
 * requirements-worker.txt 的运行时依赖对齐）。只验 {@code import kwikquant_worker} 不够——
 * 该包顶层无三方导入，且 PYTHONPATH 恒含仓库根，裸系统 python 也能导入成功，探不出
 * venv 依赖损坏/陈旧（git pull 新增依赖是最常见场景）。
 *
 * <p>门禁 fail-closed：自检完成前 {@link #isAvailable()} 为 false（detail "自检进行中"），
 * 避免 @Async 自检未完成时环境损坏的任务穿透 7305 前置拒绝。自检关闭视为运维显式信任环境，
 * 构造时即可用。
 *
 * <p>自检跑在 @Async 线程：首次自动搭建含 pip install（约 1-3 分钟），不能阻塞应用启动。
 * 搭建窗口内提交回测前置拒绝（7305）、/doctor 均透出"正在自动准备"。搭建失败或裸命令名
 * （如 python3，PATH 里没有）退回手动指引。测试直接调用 {@link #selfCheck()} 绕过代理，
 * 同步执行，便于断言。
 *
 * <p>仅 subprocess runner 生效（dev/test 缺省）；prod docker runner 由容器健康探针负责，
 * 不加载本 bean。开关：{@code kwikquant.worker.self-check.enabled}（默认 true）、
 * {@code kwikquant.worker.auto-setup}（默认 true）。
 */
@Component
@ConditionalOnProperty(name = "kwikquant.backtest.runner", havingValue = "subprocess", matchIfMissing = true)
public class BacktestWorkerHealthChecker {

    private static final Logger log = LoggerFactory.getLogger(BacktestWorkerHealthChecker.class);
    private static final long CHECK_TIMEOUT_SEC = 20;

    /** 依赖探测导入面：与 requirements-worker.txt 运行时依赖一致（测试依赖不入探测）。 */
    static final String PROBE_IMPORTS = "import kwikquant_worker, httpx, numpy, pandas, requests, websockets";

    /** 自动搭建中的文案标记；{@link StrategyTemplateService} 用它把首回测降级文案区分为"稍后重试"。 */
    public static final String AUTO_SETUP_MARKER = "正在自动";

    /** 自检进行中窗口的文案标记；与 {@link #AUTO_SETUP_MARKER} 同为过渡态口径的消费依据。 */
    public static final String SELF_CHECK_MARKER = "自检进行中";

    private final SubprocessExecutor executor;
    private final WorkerEnvironmentProvisioner provisioner;
    private final ApplicationEventPublisher eventPublisher;
    private final String pythonCommand;
    private final boolean enabled;
    private final boolean autoSetup;

    private volatile boolean available;
    private volatile String detail;
    private volatile boolean settled;

    public BacktestWorkerHealthChecker(
            SubprocessExecutor executor,
            WorkerEnvironmentProvisioner provisioner,
            ApplicationEventPublisher eventPublisher,
            @Value("${kwikquant.worker.python-command:}") String pythonCommand,
            @Value("${kwikquant.worker.self-check.enabled:true}") boolean enabled,
            @Value("${kwikquant.worker.auto-setup:true}") boolean autoSetup) {
        this.executor = executor;
        this.provisioner = provisioner;
        this.eventPublisher = eventPublisher;
        this.pythonCommand = pythonCommand;
        this.enabled = enabled;
        this.autoSetup = autoSetup;
        // fail-closed：自检是 @Async 的，完成前环境好坏未知，先拒绝再放行；
        // 关闭自检 = 运维显式信任环境（如测试/CI），直接可用
        if (enabled) {
            this.available = false;
            this.detail = SELF_CHECK_MARKER + "，请稍候重试";
        } else {
            this.available = true;
            this.detail = "自检已关闭（kwikquant.worker.self-check.enabled=false）";
            this.settled = true;
        }
    }

    /** App ready 后自检（异步，首次自动搭建耗时不阻塞启动）：探测 → 缺失则搭建 → 复探。 */
    @Async
    @EventListener(ApplicationReadyEvent.class)
    public void selfCheck() {
        if (!enabled) {
            return;
        }
        try {
            doSelfCheck();
        } catch (RuntimeException e) {
            // 兜底：任何未预期异常都不能让门禁停在未知态
            log.error("backtest worker self-check error", e);
            markUnavailable("自检异常：" + e.getMessage() + "；请执行 scripts/setup-worker-env.sh 排查后重启后端");
        }
    }

    private void doSelfCheck() {
        if (pythonCommand == null || pythonCommand.isBlank()) {
            markUnavailable("kwikquant.worker.python-command 未配置（当前 profile 的 kwikquant.worker 段缺失）；"
                    + "请配置该属性，或执行 scripts/setup-worker-env.sh 准备环境后重启后端");
            return;
        }
        Probe probe = probe();
        if (probe.allOk()) {
            markAvailable("ok: " + pythonCommand);
            return;
        }

        boolean canAutoSetup = autoSetup && WorkerEnvironmentProvisioner.provisionable(pythonCommand);
        if (!canAutoSetup) {
            markUnavailable(manualFixHint(probe));
            return;
        }

        // 自动搭建（异步线程内同步跑完）：解释器缺失 → 建 venv + 装依赖；仅依赖不全 → 补装
        boolean createVenv = !probe.versionOk;
        available = false;
        detail = createVenv ? "回测运行环境正在自动准备（创建虚拟环境并安装依赖，首次约 1-3 分钟），请稍候重试" : "回测运行环境正在自动补装依赖（约 1-3 分钟），请稍候重试";
        log.info("backtest worker 环境缺失，开始自动搭建: {} (createVenv={})", pythonCommand, createVenv);
        ProvisionResult provision = provisioner.provision(pythonCommand, createVenv);
        if (!provision.success()) {
            // detail 已带针对性原因与提示，这里只补统一出路，避免同一条消息里脚本名/重启重复出现
            markUnavailable("自动搭建失败：" + provision.detail()
                    + "；也可执行 scripts/setup-worker-env.sh 查看同样步骤，"
                    + "或 export KWIKQUANT_WORKER_PYTHON=<venv>/bin/python 指向可用环境，完成后重启后端");
            return;
        }
        Probe recheck = probe();
        if (!recheck.allOk()) {
            markUnavailable("环境已搭建但复验仍未通过（" + recheck.describeFailure() + "）；请执行 scripts/setup-worker-env.sh 排查，或手工运行 "
                    + pythonCommand + " --version 定位，完成后重启后端");
            return;
        }
        markAvailable("ok: " + pythonCommand + "（自动搭建，" + provision.detail() + "）");
    }

    public boolean isAvailable() {
        return available;
    }

    /** 自检是否已跑到明确结论（可用/不可用）；未落定时 {@link BacktestTaskRecovery} 暂缓入队。 */
    public boolean settled() {
        return settled;
    }

    public String detail() {
        return detail;
    }

    /** 两步探测：解释器 --version + 运行时依赖导入（{@link #PROBE_IMPORTS}）。 */
    private Probe probe() {
        SubprocessResult version = executor.run(List.of(pythonCommand, "--version"), Map.of(), null, CHECK_TIMEOUT_SEC);
        boolean versionOk = !version.timedOut() && version.exitCode() == 0;
        SubprocessResult importCheck = null;
        boolean importOk = false;
        if (versionOk) {
            importCheck = executor.run(List.of(pythonCommand, "-c", PROBE_IMPORTS), Map.of(), null, CHECK_TIMEOUT_SEC);
            importOk = !importCheck.timedOut() && importCheck.exitCode() == 0;
        }
        return new Probe(versionOk, version, importOk, importCheck);
    }

    /** 不可自动搭建时的手动指引：按失败阶段给不同文案。 */
    private String manualFixHint(Probe probe) {
        if (!probe.versionOk) {
            boolean bareCommand = !pythonCommand.contains("/");
            return "python 解释器不可执行：" + pythonCommand + "（" + describe(probe.version) + "）；修复："
                    + (bareCommand
                            ? "安装 python3（≥3.11），或 export KWIKQUANT_WORKER_PYTHON=<venv>/bin/python，完成后重启后端"
                            : "执行 scripts/setup-worker-env.sh 一键搭建，或 export KWIKQUANT_WORKER_PYTHON=<venv>/bin/python，完成后重启后端");
        }
        return "回测运行依赖不完整：" + describe(probe.importCheck) + "；修复：执行 scripts/setup-worker-env.sh（或手工 " + pythonCommand
                + " -m pip install -r requirements-worker.txt），完成后重启后端";
    }

    private void markAvailable(String summary) {
        available = true;
        detail = summary;
        settled = true;
        log.info("backtest worker self-check passed: {}", summary);
        eventPublisher.publishEvent(new WorkerEnvironmentSettledEvent(true));
    }

    private void markUnavailable(String reason) {
        available = false;
        detail = reason;
        settled = true;
        log.error("backtest worker self-check FAILED: {}。提交回测将返 7305。", reason);
        eventPublisher.publishEvent(new WorkerEnvironmentSettledEvent(false));
    }

    private static String describe(SubprocessResult r) {
        if (r == null) {
            return "未执行";
        }
        if (r.timedOut()) {
            return "超时";
        }
        String err = r.stderr() == null ? "" : r.stderr().trim();
        if (err.startsWith("spawn failed")) {
            // spawn 原文（Cannot run program ...）对用户没有信息量，归一成可操作的结论
            return "命令不存在或不可执行";
        }
        return "exit " + r.exitCode() + (err.isEmpty() ? "" : " " + err);
    }

    /** 两步探测结果快照。 */
    private record Probe(boolean versionOk, SubprocessResult version, boolean importOk, SubprocessResult importCheck) {
        boolean allOk() {
            return versionOk && importOk;
        }

        String describeFailure() {
            if (!versionOk) {
                return "解释器不可执行：" + describe(version);
            }
            return "依赖导入失败：" + describe(importCheck);
        }
    }
}
