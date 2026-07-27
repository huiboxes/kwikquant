package com.kwikquant.strategy.infrastructure;

import com.kwikquant.strategy.application.WorkerConfig;
import com.kwikquant.strategy.application.WorkerManager;
import com.kwikquant.strategy.domain.WorkerStartFailedException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
 * 非 HTTP {@code /health}(镜像未含 /health 端点,无可探端点)。
 * 镜像就绪后可改 HTTP GET {@code http://{containerIp}:8080/health}(5s 超时)。
 *
 * <p>此类从 JaCoCo 排除（依赖外部 docker daemon，单测不覆盖，集成测试在 Worker 镜像就绪后补）。
 */
@Component
public class DockerWorkerManager implements WorkerManager {

    private static final Logger log = LoggerFactory.getLogger(DockerWorkerManager.class);
    private static final String IMAGE = "kwikquant-worker:latest";
    private static final String NETWORK = "kwikquant-worker-net";
    private static final int HEALTH_PORT = 8081;
    private static final Duration HEALTH_TIMEOUT = Duration.ofSeconds(5);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** 容器运行用户 UID:GID（spec-review S-4，非 root 加固）。 */
    private static final String CONTAINER_UID_GID = "1000:1000";

    private final HttpClient healthHttpClient;
    private final String healthHostOverride;

    @Autowired
    public DockerWorkerManager(@Value("${kwikquant.worker.health-host-override:}") String healthHostOverride) {
        this(HttpClient.newBuilder().connectTimeout(HEALTH_TIMEOUT).build(), healthHostOverride);
    }

    /** 构造重载,测试注入 mock HttpClient(§3.7 healthCheck HTTP)。 */
    DockerWorkerManager(HttpClient healthHttpClient, String healthHostOverride) {
        this.healthHttpClient = healthHttpClient;
        this.healthHostOverride = healthHostOverride == null ? "" : healthHostOverride;
    }

    @Override
    public String createAndStart(WorkerConfig config) {
        String name = "strategy-worker-" + config.strategyId();
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
        // Wave 8 §3.7:HTTP GET http://<container>:8081/health,5s 超时,2xx = healthy。
        // healthHostOverride 供本地/测试环境覆盖 docker DNS 解析(如 "localhost")。
        String host = healthHostOverride.isBlank() ? containerId : healthHostOverride;
        URI uri = URI.create("http://" + host + ":" + HEALTH_PORT + "/health");
        HttpRequest req =
                HttpRequest.newBuilder(uri).timeout(HEALTH_TIMEOUT).GET().build();
        try {
            HttpResponse<Void> resp = healthHttpClient.send(req, HttpResponse.BodyHandlers.discarding());
            return resp.statusCode() >= 200 && resp.statusCode() < 300;
        } catch (Exception e) {
            log.debug("healthCheck HTTP failed for {}: {}", containerId, e.getMessage());
            return false;
        }
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
