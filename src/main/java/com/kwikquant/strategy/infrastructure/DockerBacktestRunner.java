package com.kwikquant.strategy.infrastructure;

import com.kwikquant.strategy.application.BacktestResult;
import com.kwikquant.strategy.application.BacktestResultParser;
import com.kwikquant.strategy.application.BacktestRunRequest;
import com.kwikquant.strategy.application.BacktestRunner;
import com.kwikquant.strategy.application.SubprocessExecutor;
import com.kwikquant.strategy.application.SubprocessResult;
import com.kwikquant.strategy.domain.BacktestRunnerException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 回测 Runner 之一(实现 {@link BacktestRunner} SPI):每任务一个隔离 Docker 容器执行
 * ({@code docker run -i ... worker-image --mode=backtest}),prod 默认({@code kwikquant.backtest.runner=docker})。
 *
 * <p><b>安全模型</b>:用户不可信 Python 代码在独立容器执行——非特权 UID、read-only rootfs、
 * cap-drop ALL、pids/mem/cpu 限额、独立网络(仅可达 app API),与 app 进程彻底隔离,
 * 杜绝子进程方案 /proc 读宿主 env 窃取平台密钥的资损面。
 *
 * <p><b>配置下发走 stdin 而非 env</b>:策略源码可达 1MB,超 Linux argv+env ~128KB 上限;
 * 且 env 经 docker inspect 对宿主 docker 组可见。stdin 通道两者皆免。
 *
 * <p><b>执行模型</b>:前台阻塞(不带 -d),docker CLI 转发容器 stdout → section8 协议与
 * {@link BacktestResultParser} 零改动;超时后 docker CLI 被杀但容器可能残留 → 显式 docker rm -f 收尾;
 * 前置 rm -f 同名容器幂等清理(仿 DockerWorkerManager,不依赖内存 registry)。
 *
 * <p>JaCoCo 排除(docker CLI 启动不可单测,同 DockerWorkerManager;逻辑经 mock SubprocessExecutor 覆盖)。
 */
@Component
@ConditionalOnProperty(name = "kwikquant.backtest.runner", havingValue = "docker")
public class DockerBacktestRunner implements BacktestRunner {

    /** 与 DockerWorkerManager.NETWORK 一致:worker 容器共享网络,可达 app 容器名。 */
    static final String NETWORK = "kwikquant-worker-net";

    /** 容器命名前缀 + taskId,孤儿识别与幂等清理的依据。 */
    static final String CONTAINER_NAME_PREFIX = "backtest-worker-";

    /** 与 DockerWorkerManager 对齐的不可信代码执行加固旗标。 */
    static final String CONTAINER_UID_GID = "1000:1000";

    private static final long CLEANUP_TIMEOUT_SEC = 30;

    private final SubprocessExecutor executor;
    private final ObjectMapper objectMapper;
    private final String image;
    private final String apiBase;
    private final String memory;
    private final String cpus;
    private final long timeoutSec;

    public DockerBacktestRunner(
            SubprocessExecutor executor,
            ObjectMapper objectMapper,
            // 复用 runner worker 镜像(kwikquant-worker.Dockerfile 双模式 ENTRYPOINT 支持 --mode=backtest)
            @Value("${kwikquant.worker.image:kwikquant-worker:latest}") String image,
            // 容器网络内回连 app:prod = http://kwikquant-app:8080,dev = http://host.docker.internal:8080
            @Value("${kwikquant.worker.api-base-url:}") String apiBase,
            @Value("${kwikquant.backtest.container-memory:2g}") String memory,
            @Value("${kwikquant.backtest.container-cpus:1}") String cpus,
            @Value("${kwikquant.worker.timeout-sec:3600}") long timeoutSec) {
        this.executor = executor;
        this.objectMapper = objectMapper;
        this.image = image;
        this.apiBase = apiBase;
        this.memory = memory;
        this.cpus = cpus;
        this.timeoutSec = timeoutSec;
    }

    @Override
    public BacktestResult run(BacktestRunRequest request) {
        if (image == null || image.isBlank() || apiBase == null || apiBase.isBlank()) {
            throw new BacktestRunnerException("backtest docker runner 未配置(kwikquant.worker.image/api-base-url 缺失;"
                    + "检查当前 profile 的 kwikquant.worker 配置)");
        }
        String containerName = CONTAINER_NAME_PREFIX + request.taskId();
        // 幂等清理上次崩溃残留(--rm 只覆盖正常退出;daemon 重启/强杀可能留下同名容器)
        cleanupQuietly(containerName);
        String taskConfig = objectMapper.writeValueAsString(request);
        List<String> command = buildDockerRunCommand(containerName);
        Map<String, String> env = Map.of("KWIKQUANT_API_BASE", apiBase);
        SubprocessResult result;
        try {
            result = executor.run(command, env, taskConfig, timeoutSec);
        } finally {
            // 超时路径:destroyForcibly 只杀 docker CLI 客户端,容器本体可能仍在跑 → 强制移除。
            // 正常退出路径 --rm 已清理,rm -f 对不存在容器返回非 0,quietly 忽略。
            cleanupQuietly(containerName);
        }
        return BacktestResultParser.parse(result, objectMapper);
    }

    List<String> buildDockerRunCommand(String containerName) {
        List<String> cmd = new ArrayList<>();
        cmd.add("docker");
        cmd.add("run");
        cmd.add("-i"); // stdin 下发 TASK_CONFIG_JSON(含策略源码 + serviceToken)
        cmd.add("--init");
        cmd.add("--rm");
        cmd.add("--name");
        cmd.add(containerName);
        cmd.add("--user");
        cmd.add(CONTAINER_UID_GID);
        cmd.add("--read-only");
        cmd.add("--security-opt=no-new-privileges");
        cmd.add("--cap-drop=ALL");
        cmd.add("--pids-limit=256");
        cmd.add("--tmpfs");
        cmd.add("/tmp:rw,noexec,nosuid,size=64m");
        cmd.add("--memory=" + memory);
        cmd.add("--memory-swap=" + memory); // 禁 swap,内存限额才有实际约束力
        cmd.add("--cpus=" + cpus);
        cmd.add("--network");
        cmd.add(NETWORK);
        cmd.add("--no-healthcheck"); // backtest 模式不起 HealthServer,屏蔽镜像 HEALTHCHECK 防 unhealthy 噪音
        cmd.add("--env");
        cmd.add("KWIKQUANT_API_BASE=" + apiBase);
        cmd.add(image);
        cmd.add("--mode=backtest");
        return List.copyOf(cmd);
    }

    private void cleanupQuietly(String containerName) {
        try {
            executor.run(List.of("docker", "rm", "-f", containerName), Map.of(), null, CLEANUP_TIMEOUT_SEC);
        } catch (Exception ignored) {
            // 清理失败不阻断主流程:容器不存在/daemon 抖动都属正常场景
        }
    }
}
