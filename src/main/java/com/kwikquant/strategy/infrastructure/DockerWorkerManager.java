package com.kwikquant.strategy.infrastructure;

import com.kwikquant.strategy.application.SubprocessExecutor;
import com.kwikquant.strategy.application.SubprocessResult;
import com.kwikquant.strategy.application.WorkerConfig;
import com.kwikquant.strategy.application.WorkerManager;
import com.kwikquant.strategy.domain.WorkerStartFailedException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Docker-based {@link WorkerManager} 实现。通过 {@link SubprocessExecutor} 执行
 * {@code docker run/stop/rm/ps}(List 命令模式,不拼 shell),复用 backtest 的命令执行 SPI
 * (超时 + 异步 drain + stdout 截断),而非各自裸 {@code ProcessBuilder}(原实现 waitFor 无超时、
 * 无异步 drain,daemon 异常会永久阻塞调用线程)。
 *
 * <p><b>容器安全加固</b>:与 {@link DockerBacktestRunner} 对齐——runner 同样执行用户 {@code on_bar}
 * (exec 用户源码),加固不对等是安全缺口。旗标:{@code --user 1000:1000 --read-only
 * --security-opt=no-new-privileges --cap-drop=ALL --pids-limit=256 --tmpfs /tmp
 * --memory --memory-swap(禁 swap) --cpus --network}。容器名用
 * {@code strategy-worker-{strategyId}}(Long 安全),env 值走 ObjectMapper 序列化。
 *
 * <p><b>配置下发</b>:runner 配置(sourceCode 含在内)走 {@code --env TASK_CONFIG_JSON}
 * ({@code docker run -d} 后台,CLI 秒返回 container id)。sourceCode 进 env 有 ~128KB argv+env
 * 上限风险——由 Wave 1.4 后续(拉取式 bootstrap / stdin 下发)统一解决,本批先加固旗标与执行超时。
 *
 * <p><b>healthCheck</b>:HTTP 探活 via {@link WorkerHealthProbe}(GET worker {@code /health} 读存活
 * 信号),而非 {@code docker inspect}(只看进程存活=假健康:容器活但策略卡死/WS 断/连续下单失败仍报
 * healthy)。{@link #isWorkerHealthy} 纯函数判定(status ok + lastWsMsgAt 在阈值内 + 连续下单失败未越限)。
 * worker /health 由 {@code health_signals.py} 暴露(:8081),app 经 worker-net(prod 双网卡)可达容器名。
 *
 * <p>此类逻辑可经 mock {@link SubprocessExecutor}(docker 命令)+ mock {@link WorkerHealthProbe}(HTTP 探活)
 * 单测覆盖(同 {@code DockerBacktestRunnerTest} 模式)。
 */
@Component
public class DockerWorkerManager implements WorkerManager {

    private static final Logger log = LoggerFactory.getLogger(DockerWorkerManager.class);

    static final String NETWORK = "kwikquant-worker-net";
    /** 容器运行用户 UID:GID(非 root 加固,与 DockerBacktestRunner 对齐)。 */
    static final String CONTAINER_UID_GID = "1000:1000";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** docker run 启动超时(镜像应已预构建;拉新镜像属部署问题非运行时,30s 足够 daemon 返回)。 */
    private static final long START_TIMEOUT_SEC = 30;

    private static final long STOP_TIMEOUT_SEC = 15;
    private static final long RM_TIMEOUT_SEC = 15;
    /** docker ps 列容器名超时(孤儿 GC 扫 strategy-worker-*)。 */
    private static final long LIST_TIMEOUT_SEC = 15;

    private final SubprocessExecutor executor;
    private final WorkerHealthProbe healthProbe;
    private final String image;
    /** WS 消息 stale 阈值(ms):超过判不健康(WS 真断;WS 持续推 ticker,与 bar interval 无关)。 */
    private final long wsStaleMs;
    /** 连续下单失败上限:达到判不健康(策略/账户持续故障)。 */
    private final int maxOrderFailures;

    public DockerWorkerManager(
            SubprocessExecutor executor,
            WorkerHealthProbe healthProbe,
            @Value("${kwikquant.worker.image:kwikquant-worker:latest}") String image,
            @Value("${kwikquant.worker.health.ws-stale-ms:300000}") long wsStaleMs,
            @Value("${kwikquant.worker.health.max-order-failures:5}") int maxOrderFailures) {
        this.executor = executor;
        this.healthProbe = healthProbe;
        this.image = image;
        this.wsStaleMs = wsStaleMs;
        this.maxOrderFailures = maxOrderFailures;
    }

    @Override
    public String createAndStart(WorkerConfig config) {
        String name = CONTAINER_NAME_PREFIX + config.strategyId();
        // 防孤儿容器:不依赖内存 registry(reconcile 后/并发时 registry 可能无旧记录),
        // 直接 docker rm -f 同名容器强制清理(运行中 SIGKILL),确保 docker run 不撞同名冲突。
        removeQuietly(name);
        List<String> command = buildDockerRunCommand(config);
        SubprocessResult result = executor.run(command, Map.of(), null, START_TIMEOUT_SEC);
        if (result.timedOut()) {
            throw new WorkerStartFailedException(
                    config.strategyId(), "docker run timed out (> " + START_TIMEOUT_SEC + "s)", null);
        }
        if (result.exitCode() != 0) {
            throw new WorkerStartFailedException(
                    config.strategyId(), "docker run failed: " + result.stdout().trim(), null);
        }
        return name;
    }

    /**
     * 构造 {@code docker run} 命令(纯函数,可单测旗标)。runner 旗标与 {@link DockerBacktestRunner}
     * 对齐:不可信 {@code on_bar} 同样执行用户源码,加固不对等是安全缺口。显式 {@code --mode=runner}
     * 不依赖镜像 CMD 默认(对齐 backtest 显式 {@code --mode=backtest})。
     */
    List<String> buildDockerRunCommand(WorkerConfig config) {
        String taskConfigJson;
        try {
            taskConfigJson = OBJECT_MAPPER.writeValueAsString(Map.of(
                    "strategyId", config.strategyId(),
                    "strategyName", config.strategyName() == null ? "" : config.strategyName(),
                    "sourceCode", config.sourceCode() == null ? "" : config.sourceCode(),
                    "symbol", config.symbol(),
                    "exchange", config.exchange(),
                    "marketType", config.marketType(),
                    "intervalValue", config.intervalValue(),
                    "parameters", config.parameters() == null ? "{}" : config.parameters(),
                    "apiBaseUrl", config.apiBaseUrl()));
        } catch (Exception e) {
            throw new WorkerStartFailedException(
                    config.strategyId(), "failed to serialize task config: " + e.getMessage(), e);
        }
        List<String> cmd = new ArrayList<>(List.of(
                "docker",
                "run",
                "-d",
                "--init",
                "--rm",
                "--name",
                CONTAINER_NAME_PREFIX + config.strategyId(),
                "--user",
                CONTAINER_UID_GID,
                "--read-only",
                "--security-opt=no-new-privileges",
                "--cap-drop=ALL",
                "--pids-limit=256",
                "--tmpfs",
                "/tmp:rw,noexec,nosuid,size=64m",
                "--memory=" + config.memoryLimitMb() + "m",
                "--memory-swap=" + config.memoryLimitMb() + "m",
                "--cpus=" + config.cpuLimit(),
                "--network",
                NETWORK,
                "--env",
                "TASK_CONFIG_JSON=" + taskConfigJson,
                "--env",
                "WORKER_SERVICE_TOKEN=" + config.serviceToken(),
                "--env",
                "KWIKQUANT_API_BASE=" + config.apiBaseUrl(),
                image,
                "--mode=runner"));
        return List.copyOf(cmd);
    }

    @Override
    public void stop(String containerId) {
        runQuietly(List.of("docker", "stop", containerId), STOP_TIMEOUT_SEC);
    }

    @Override
    public void remove(String containerId) {
        runQuietly(List.of("docker", "rm", "-f", containerId), RM_TIMEOUT_SEC);
    }

    @Override
    public List<String> listStrategyWorkerContainers() {
        try {
            SubprocessResult result = executor.run(
                    List.of(
                            "docker",
                            "ps",
                            "-a",
                            "--filter",
                            "name=" + CONTAINER_NAME_PREFIX,
                            "--format",
                            "{{.Names}}"),
                    Map.of(),
                    null,
                    LIST_TIMEOUT_SEC);
            if (result.exitCode() != 0) {
                log.debug(
                        "docker ps (listStrategyWorkerContainers) failed: {}",
                        result.stdout().trim());
                return List.of();
            }
            return result.stdout()
                    .lines()
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
        } catch (Exception e) {
            log.debug("docker ps (listStrategyWorkerContainers) exception", e);
            return List.of();
        }
    }

    private void removeQuietly(String containerId) {
        runQuietly(List.of("docker", "rm", "-f", containerId), RM_TIMEOUT_SEC);
    }

    @Override
    public boolean healthCheck(String containerId) {
        Optional<WorkerHealthSnapshot> snap = healthProbe.probe(containerId);
        return snap.isPresent() && isWorkerHealthy(snap.get(), System.currentTimeMillis(), wsStaleMs, maxOrderFailures);
    }

    /**
     * worker 健康判定(纯函数,可单测)。规则:
     *
     * <ul>
     *   <li>快照缺失 → 不健康(连不上=容器死/网络断)
     *   <li>{@code status != "ok"} → 不健康
     *   <li>{@code lastWsMsgAt == null} → 不健康(WS 尚未连上;持续 null=WS 故障,下轮 healthCheck 触发 restart)
     *   <li>{@code now - lastWsMsgAt > wsStaleMs} → 不健康(WS 断 >5min;WS 持续推 ticker,
     *       stale 与 bar interval 无关,5min 阈值过滤秒级抖动)
     *   <li>{@code consecutiveOrderFailures >= maxOrderFailures} → 不健康(策略/账户持续下单失败)
     * </ul>
     *
     * <p><b>不用 {@code lastBarAt}</b>:bar 间隔 1m–1d 变化大,固定阈值会误判;{@code lastWsMsgAt}
     * (WS 持续性)是更稳的健康信号。{@code lastBarAt} 留 /health 诊断。
     *
     * <p><b>去抖</b>:本判定不在 healthCheck 层去抖(5min stale 阈值已保守)。restart 退避/首次观察留
     * 后续——依赖本判定的 stale 语义先落地,且去抖影响现有 healthCheck 测试语义需单独谨慎设计。
     */
    static boolean isWorkerHealthy(WorkerHealthSnapshot snap, long nowMs, long wsStaleMs, int maxOrderFailures) {
        if (snap == null) {
            return false;
        }
        if (!"ok".equals(snap.status())) {
            return false;
        }
        Long lastWs = snap.lastWsMsgAt();
        if (lastWs == null) {
            return false; // WS 尚未连上
        }
        if (nowMs - lastWs > wsStaleMs) {
            return false; // WS 断超阈值
        }
        Integer failures = snap.consecutiveOrderFailures();
        if (failures != null && failures >= maxOrderFailures) {
            return false; // 持续下单失败
        }
        return true;
    }

    private void runQuietly(List<String> cmd, long timeoutSec) {
        try {
            executor.run(cmd, Map.of(), null, timeoutSec);
        } catch (Exception e) {
            log.debug("docker cmd failed (ignored): {}", cmd, e);
        }
    }
}
