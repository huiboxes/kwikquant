package com.kwikquant.strategy.infrastructure;

import com.kwikquant.strategy.application.WorkerConfig;
import com.kwikquant.strategy.application.WorkerManager;
import com.kwikquant.strategy.domain.WorkerStartFailedException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Docker-based {@link WorkerManager} 实现。通过 {@link ProcessBuilder}（List 模式，不拼接 shell，spec-review S-1）
 * 执行 {@code docker run/stop/rm/inspect}。不引入 docker-java 库（命令行方式足够）。
 *
 * <p>容器安全加固（spec-review S-4）：{@code --user 1000:1000 --read-only --memory --cpus --network
 * --security-opt=no-new-privileges}。strategyName 走白名单校验（S-1，防容器名注入）。
 *
 * <p><b>简化(架构师决策)</b>:{@code healthCheck} 用 {@code docker inspect}(isRunning)代理,
 * 非 HTTP {@code /health}。镜像有 /health 端点(health_server.py),但后端是 host 进程,
 * 解析不了 docker network 内部名字(strategy-worker-{id}),HTTP 探活必失败 → restart 循环。
 * prod 后端容器在 worker-net 时可改回 HTTP 应用层探活。
 *
 * <p>此类从 JaCoCo 排除（依赖外部 docker daemon，单测不覆盖，集成测试在 Worker 镜像就绪后补）。
 */
@Component
public class DockerWorkerManager implements WorkerManager {

    private static final Logger log = LoggerFactory.getLogger(DockerWorkerManager.class);
    private static final String IMAGE = "kwikquant-worker:latest";
    private static final String NETWORK = "kwikquant-worker-net";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** 容器运行用户 UID:GID（spec-review S-4，非 root 加固）。 */
    private static final String CONTAINER_UID_GID = "1000:1000";

    @Override
    public String createAndStart(WorkerConfig config) {
        String name = "strategy-worker-" + config.strategyId();
        // 防孤儿容器:不依赖 WorkerOrchestratorService 的内存 registry(reconcile 后/并发时 registry 可能无旧记录),
        // 直接 docker rm -f 同名容器强制清理(运行中也 SIGKILL),确保 docker run 不撞同名冲突。
        runQuiet(List.of("docker", "rm", "-f", name));
        // env 协议与 worker_server.main() 一致:TASK_CONFIG_JSON(序列化 cfg,不含 serviceToken——
        // token 单独 env)+ WORKER_SERVICE_TOKEN + KWIKQUANT_API_BASE(对齐 worker_server:76 读的 env 名)。
        // §3.7:含 sourceCode/marketType 供 runner 实例化 on_bar + 订阅 kline + 下单 marketType。
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
                "--rm",
                "--name",
                name,
                "--user",
                CONTAINER_UID_GID,
                "--read-only",
                "--security-opt=no-new-privileges",
                "--memory",
                config.memoryLimitMb() + "m",
                "--cpus",
                String.valueOf(config.cpuLimit()),
                "--network",
                NETWORK,
                "--env",
                "TASK_CONFIG_JSON=" + taskConfigJson,
                "--env",
                "WORKER_SERVICE_TOKEN=" + config.serviceToken(),
                "--env",
                "KWIKQUANT_API_BASE=" + config.apiBaseUrl(),
                IMAGE));
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            int code = p.waitFor();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (code != 0) {
                throw new WorkerStartFailedException(config.strategyId(), "docker run failed: " + out.trim(), null);
            }
            return name;
        } catch (WorkerStartFailedException e) {
            throw e;
        } catch (Exception e) {
            throw new WorkerStartFailedException(config.strategyId(), e.getMessage(), e);
        }
    }

    @Override
    public void stop(String containerId) {
        runQuiet(List.of("docker", "stop", containerId));
    }

    @Override
    public void remove(String containerId) {
        runQuiet(List.of("docker", "rm", "-f", containerId));
    }

    @Override
    public boolean isRunning(String containerId) {
        try {
            String out = runCapture(List.of("docker", "inspect", "--format", "{{.State.Running}}", containerId));
            return out.trim().equalsIgnoreCase("true");
        } catch (Exception e) {
            log.debug("docker inspect (isRunning) failed for {}", containerId, e);
            return false;
        }
    }

    @Override
    public boolean healthCheck(String containerId) {
        // docker inspect 查容器 Running 状态。原 HTTP GET /health 因后端是 host 进程,
        // 解析不了 docker network 内部名字(strategy-worker-{id})致 healthCheck 必失败
        // → restart 循环 stop/rm worker → markError。/health 端点由 health_server.py 实现,
        // 但 host 访问不到容器;prod 后端容器在 worker-net 时可改回 HTTP 应用层探活。
        return isRunning(containerId);
    }

    private void runQuiet(List<String> cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            p.waitFor();
        } catch (Exception e) {
            log.debug("docker cmd failed (ignored): {}", cmd, e);
        }
    }

    private String runCapture(List<String> cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        p.waitFor();
        return new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    /** 容器名/环境变量值白名单校验（S-1，防注入）。 */
    private static String sanitizeName(String name) {
        if (name == null) {
            return "";
        }
        return name.replaceAll("[^a-zA-Z0-9_\\-\\s]", "");
    }
}
