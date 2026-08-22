package com.kwikquant.strategy.application;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 回测 worker Python 环境自动搭建。健康自检（{@link BacktestWorkerHealthChecker}）发现解释器
 * 缺失/依赖不全时委托这里补齐：创建 venv（python3 -m venv）→ 安装依赖（python -m pip install
 * -r requirements-worker.txt）。让"fresh clone 即能回测"成立，不再要求用户先手工建 venv。
 *
 * <p>只对路径形态命令（.venv/bin/python 类）生效，见 {@link #provisionable}；裸命令名
 * （python3）属 PATH 缺失，自动搭建帮不上，只能引导用户装 python3。venv 根目录从解释器路径
 * 反推（.venv/bin/python → .venv），保证搭建产物与 {@code python-command} 消费的完全是同一份。
 *
 * <p>安全护栏（自动搭建会执行 {@code venv --clear} 与 pip install，目标必须可控）：
 * <ul>
 *   <li>已存在的目标目录必须含 pyvenv.cfg（确是虚拟环境）才允许 --clear——防止误配
 *       {@code KWIKQUANT_WORKER_PYTHON=/usr/bin/pythonX} 时把 /usr 清掉；</li>
 *   <li>不存在的目标不允许落在系统根目录下（只能在用户自己的目录里新建）；</li>
 *   <li>补装依赖只进虚拟环境，不向非 venv 解释器（如系统 python）pip install；</li>
 *   <li>建 venv 前校验系统 python3 ≥3.11，与 setup-worker-env.sh、文档口径一致。</li>
 * </ul>
 *
 * <p>仅 subprocess runner 加载；prod docker runner 的镜像自带环境，无搭建需求。
 * 受限网络：提前 {@code export PIP_INDEX_URL=<镜像源>}（搭建进程透传），或手工跑
 * {@code scripts/setup-worker-env.sh} 排查。
 */
@Component
@ConditionalOnProperty(name = "kwikquant.backtest.runner", havingValue = "subprocess", matchIfMissing = true)
public class WorkerEnvironmentProvisioner {

    static final String REQUIREMENTS_FILE = "requirements-worker.txt";
    private static final long VERSION_CHECK_TIMEOUT_SEC = 20;
    private static final long VENV_TIMEOUT_SEC = 180;
    private static final long PIP_TIMEOUT_SEC = 900;
    private static final int STDERR_TAIL_LIMIT = 300;

    /** 建 venv 前校验系统 python3 版本，与 scripts/setup-worker-env.sh 同口径。 */
    private static final String VERSION_CHECK = "import sys; sys.exit(0 if sys.version_info >= (3, 11) else 1)";

    /** 不允许在其下新建虚拟环境的系统根目录（已存在的目录由 pyvenv.cfg 检查兜底）。 */
    private static final Set<String> SYSTEM_ROOTS = Set.of(
            "/usr", "/bin", "/sbin", "/lib", "/lib64", "/etc", "/var", "/boot", "/dev", "/proc", "/sys", "/snap");

    /** pip 透传的宿主环境：镜像源/可信源 + 代理（子进程环境白名单制，不透传则受限网络装不上）。 */
    private static final List<String> PIP_PASSTHROUGH_ENV = List.of(
            "PIP_INDEX_URL",
            "PIP_EXTRA_INDEX_URL",
            "PIP_TRUSTED_HOST",
            "http_proxy",
            "https_proxy",
            "no_proxy",
            "HTTP_PROXY",
            "HTTPS_PROXY",
            "NO_PROXY");

    private final SubprocessExecutor executor;

    public WorkerEnvironmentProvisioner(SubprocessExecutor executor) {
        this.executor = executor;
    }

    /** 搭建结果；失败时 detail 含原因与修复步骤，可直接透出给用户。 */
    public record ProvisionResult(boolean success, String detail) {
        static ProvisionResult ok(String detail) {
            return new ProvisionResult(true, detail);
        }

        static ProvisionResult failed(String detail) {
            return new ProvisionResult(false, detail);
        }
    }

    /** 路径形态命令（含 /）才可自动搭建；裸命令名是 PATH 问题，只能引导手动安装。 */
    public static boolean provisionable(String pythonCommand) {
        return pythonCommand != null && pythonCommand.contains("/");
    }

    /**
     * 搭建 pythonCommand 指向的环境。同步执行至完成（首次含 pip install，约 1-3 分钟），
     * 由调用方决定在哪个线程跑（健康自检在 @Async 线程内调用，不阻塞应用启动）。
     *
     * @param createVenv true = 解释器不存在，先建 venv；false = 解释器已在但依赖不全，仅补装依赖
     */
    public ProvisionResult provision(String pythonCommand, boolean createVenv) {
        String venvDir = deriveVenvDir(pythonCommand);
        if (venvDir == null) {
            return ProvisionResult.failed("无法从解释器路径 " + pythonCommand + " 反推 venv 根目录，请改为 <venv>/bin/python 形式");
        }
        Path venvPath = Path.of(venvDir).toAbsolutePath().normalize();
        ProvisionResult guard = checkTargetDir(venvPath, pythonCommand);
        if (guard != null) {
            return guard;
        }
        if (createVenv) {
            SubprocessResult versionCheck =
                    executor.run(List.of("python3", "-c", VERSION_CHECK), Map.of(), null, VERSION_CHECK_TIMEOUT_SEC);
            if (versionCheck.timedOut() || versionCheck.exitCode() != 0) {
                return ProvisionResult.failed("系统 python3 不可用或版本过低（" + describe(versionCheck)
                        + "），需要 ≥3.11（Debian/Ubuntu：sudo apt install python3 python3-venv）");
            }
            // --clear：venv 已存在但损坏（悬空符号链接/半截初始化）时一并重建，自愈而非叠加坏状态
            SubprocessResult venv = executor.run(
                    List.of("python3", "-m", "venv", "--clear", venvPath.toString()), Map.of(), null, VENV_TIMEOUT_SEC);
            if (venv.timedOut() || venv.exitCode() != 0) {
                return ProvisionResult.failed("创建虚拟环境失败（" + describe(venv)
                        + "）；请先安装 python3（≥3.11）与 venv 模块（Debian/Ubuntu：sudo apt install python3-venv）");
            }
        }
        Map<String, String> pipEnv = new HashMap<>();
        pipEnv.put("HOME", System.getProperty("user.home", ""));
        for (String name : PIP_PASSTHROUGH_ENV) {
            String value = System.getenv(name);
            if (value != null && !value.isBlank()) {
                pipEnv.put(name, value);
            }
        }
        SubprocessResult pip = executor.run(
                List.of(pythonCommand, "-m", "pip", "install", "-r", REQUIREMENTS_FILE), pipEnv, null, PIP_TIMEOUT_SEC);
        if (pip.timedOut() || pip.exitCode() != 0) {
            return ProvisionResult.failed(
                    "依赖安装失败（" + describe(pip) + "）；网络受限可先设置 PIP_INDEX_URL=<镜像源>或代理（http_proxy/https_proxy）再重试");
        }
        return ProvisionResult.ok(
                (createVenv ? "已创建虚拟环境 " + venvPath + "，" : "") + "已按 " + REQUIREMENTS_FILE + " 安装 worker 依赖");
    }

    /**
     * 搭建目标目录安全校验，通过返 null：
     * 已存在 → 必须是虚拟环境（含 pyvenv.cfg）才允许 --clear 或往里装依赖；
     * 不存在 → 不允许落在系统目录下新建（前缀匹配，/usr 与 /usr/local 等深层路径都拦）。
     */
    private static ProvisionResult checkTargetDir(Path venvPath, String pythonCommand) {
        if (Files.exists(venvPath)) {
            if (!Files.exists(venvPath.resolve("pyvenv.cfg"))) {
                // "重启后端"等统一出路由调用方（健康自检）追加，这里只给本分支特有的处理办法
                return ProvisionResult.failed("目录 " + venvPath + " 已存在但不是 Python 虚拟环境（缺 pyvenv.cfg），"
                        + "出于安全未清除也未安装依赖；请手工确认该目录，或改用 <项目目录>/.venv。"
                        + "若使用 conda 等其他环境，可手工执行 " + pythonCommand + " -m pip install -r "
                        + REQUIREMENTS_FILE + " 装好依赖");
            }
            return null;
        }
        Path parent = venvPath.getParent();
        if (parent != null && underSystemRoot(parent)) {
            return ProvisionResult.failed(
                    "拒绝在系统目录 " + parent + " 下创建虚拟环境（解释器配置 " + pythonCommand + "）；请改用 <项目目录>/.venv，或手工准备环境");
        }
        return null;
    }

    /** 父目录（存在时先解析符号链接）是否落在系统根目录下。 */
    private static boolean underSystemRoot(Path parent) {
        Path resolved = parent;
        try {
            resolved = parent.toRealPath();
        } catch (IOException ignored) {
            // 父目录不存在或不可读时按字面路径判断
        }
        String path = resolved.toString();
        for (String root : SYSTEM_ROOTS) {
            if (path.equals(root) || path.startsWith(root + "/")) {
                return true;
            }
        }
        return false;
    }

    /** 从解释器路径反推 venv 根目录：.venv/bin/python → .venv；层级不足无法反推 → null。 */
    static String deriveVenvDir(String pythonCommand) {
        Path bin = Path.of(pythonCommand).getParent();
        Path venv = bin == null ? null : bin.getParent();
        return venv == null ? null : venv.toString();
    }

    private static String describe(SubprocessResult r) {
        if (r.timedOut()) {
            return "超时";
        }
        String err = r.stderr() == null ? "" : r.stderr().trim();
        if (err.startsWith("spawn failed")) {
            return "命令不存在或不可执行";
        }
        if (err.length() > STDERR_TAIL_LIMIT) {
            err = err.substring(0, STDERR_TAIL_LIMIT) + "…";
        }
        return "exit " + r.exitCode() + (err.isEmpty() ? "" : ": " + err);
    }
}
