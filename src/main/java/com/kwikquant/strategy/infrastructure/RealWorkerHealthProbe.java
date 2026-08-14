package com.kwikquant.strategy.infrastructure;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * HTTP 实现:{@link HttpClient} GET {@code http://{containerId}:8081/health} 反序列化为
 * {@link WorkerHealthSnapshot}。worker {@code /health} 端点由 {@code kwikquant_worker/health_server.py}
 * 暴露(监听 {@code 0.0.0.0:8081},worker-net 内 app 经容器名可达,无需 {@code -p} publish)。
 *
 * <p><b>超时</b>:connect 3s / request 5s——{@code healthCheckAll} @Scheduled 30s 并行探多 worker,
 * 单个卡死不拖垮整轮。任何失败(连接拒/超时/非 200/反序列化异常)返 {@link Optional#empty()}——由
 * {@link DockerWorkerManager#healthCheck} 统一判不健康(连不上=容器死/网络断)。
 *
 * <p><b>JSON</b>:{@code /health} 响应含 {@code strategyId} 等判定不用的字段,反序列化禁
 * {@code FAIL_ON_UNKNOWN_PROPERTIES} 容忍多余字段;record 字段(camelCase)与 JSON key 直接映射。
 *
 * <p>此类 HTTP 发送部分依赖真实 worker-net,JaCoCo 排除(同 {@link RealSubprocessExecutor});
 * 判定纯逻辑在 {@link DockerWorkerManager#isWorkerHealthy}(可单测)。
 */
@Component
class RealWorkerHealthProbe implements WorkerHealthProbe {

    private static final Logger log = LoggerFactory.getLogger(RealWorkerHealthProbe.class);

    /** worker /health 端点端口(对齐 health_server.DEFAULT_HEALTH_PORT)。 */
    private static final int HEALTH_PORT = 8081;

    /** 容忍 /health 多余字段(如 strategyId),仅反序列化判定用到的字段。 */
    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private final HttpClient httpClient =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    @Override
    public Optional<WorkerHealthSnapshot> probe(String containerId) {
        if (containerId == null || containerId.isBlank()) {
            return Optional.empty();
        }
        URI uri = URI.create("http://" + containerId + ":" + HEALTH_PORT + "/health");
        HttpRequest request =
                HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).GET().build();
        try {
            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.debug("worker /health non-200 for {}: {}", containerId, resp.statusCode());
                return Optional.empty();
            }
            String body = resp.body();
            if (body == null || body.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(OBJECT_MAPPER.readValue(body, WorkerHealthSnapshot.class));
        } catch (Exception e) {
            log.debug("worker /health probe failed for {}: {}", containerId, e.getMessage());
            return Optional.empty();
        }
    }
}
