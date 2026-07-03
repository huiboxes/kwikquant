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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Docker-based {@link WorkerManager} 实现。通过 {@link ProcessBuilder}（List 模式，不拼接 shell，spec-review S-1）
 * 执行 {@code docker run/stop/rm/inspect}。不引入 docker-java 库（命令行方式足够）。
 *
 * <p>容器安全加固（spec-review S-4）：{@code --user 1000:1000 --read-only --memory --cpus --network
 * --no-new-privileges}。strategyName 走白名单校验（S-1，防容器名注入）。
 *
 * <p><b>Wave 6 简化（架构师决策）</b>：{@code healthCheck} 用 {@code docker inspect}（isRunning）代理，
 * 非 HTTP {@code /health}。原因：Wave 6 无 Python Worker 镜像（Wave 8 构建），无 {@code /health} 端点可探。
 * Wave 8 镜像就绪后改为 HTTP GET {@code http://{containerIp}:8080/health}（5s 超时）。
 *
 * <p>此类从 JaCoCo 排除（依赖外部 docker daemon，单测不覆盖，集成测试在 Wave 8 Worker 镜像就绪后补）。
 */
@Component
public class DockerWorkerManager implements WorkerManager {

    private static final Logger log = LoggerFactory.getLogger(DockerWorkerManager.class);
    private static final String IMAGE = "kwikquant-worker:latest";
    private static final String NETWORK = "kwikquant-worker-net";
    private static final int HEALTH_PORT = 8081;
    private static final Duration HEALTH_TIMEOUT = Duration.ofSeconds(5);

    private final HttpClient healthHttpClient;
    private final String healthHostOverride;

    @Autowired
    public DockerWorkerManager(
            @Value("${kwikquant.worker.health-host-override:}") String healthHostOverride) {
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
        List<String> cmd = new ArrayList<>(List.of(
                "docker",
                "run",
                "-d",
                "--rm",
                "--name",
                name,
                "--user",
                "1000:1000",
                "--read-only",
                "--no-new-privileges",
                "--memory",
                config.memoryLimitMb() + "m",
                "--cpus",
                String.valueOf(config.cpuLimit()),
                "--network",
                NETWORK,
                "--env",
                "STRATEGY_ID=" + config.strategyId(),
                "--env",
                "SYMBOL=" + config.symbol(),
                "--env",
                "EXCHANGE=" + config.exchange(),
                "--env",
                "INTERVAL=" + config.intervalValue(),
                "--env",
                "PARAMETERS=" + config.parameters(),
                "--env",
                "STRATEGY_NAME=" + sanitizeName(config.strategyName()),
                "--env",
                "API_BASE_URL=" + config.apiBaseUrl(),
                "--env",
                "WORKER_SERVICE_TOKEN=" + config.serviceToken(),
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
        HttpRequest req = HttpRequest.newBuilder(uri).timeout(HEALTH_TIMEOUT).GET().build();
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
