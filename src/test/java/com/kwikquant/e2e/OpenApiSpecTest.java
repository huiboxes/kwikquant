package com.kwikquant.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.kwikquant.AbstractIntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.client.RestClient;

/**
 * Wave 8 契约冻结:确认 {@code /v3/api-docs} 生成有效 OpenAPI 3.0.3 JSON,前端(Wave 9 Dashboard)
 * 据此生成 typed client。SecurityConfig 已放行该端点(无需 JWT),springdoc 自动扫描 {@code @RestController}
 * 产出契约。本测试冒烟级 — 只验 spec 结构 + 关键路径存在。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OpenApiSpecTest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;

    @Test
    void v3ApiDocs_available_asOpenApi3JsonWithExpectedPaths() {
        RestClient client =
                RestClient.builder().baseUrl("http://127.0.0.1:" + port).build();
        Map<String, Object> spec = client.get().uri("/v3/api-docs").retrieve().body(Map.class);

        assertThat(spec).isNotNull();
        assertThat(spec.get("openapi")).asString().startsWith("3.");
        assertThat(spec).containsKey("info");
        assertThat(spec).containsKey("paths");

        @SuppressWarnings("unchecked")
        Map<String, Object> paths = (Map<String, Object>) spec.get("paths");
        // Wave 8 §4.1 冻结的关键端点应在 OpenAPI 中体现(至少这些主要路径,不做穷尽断言以免 UI 变动误伤)
        assertThat(paths).containsKey("/api/v1/backtests");
        assertThat(paths).containsKey("/api/v1/orders");
        // 回测虚拟账本走独立 Worker 端点(§3.1);Wave 8 关键路径
        assertThat(paths.keySet()).anyMatch(p -> p.startsWith("/api/v1/backtests/") && p.endsWith("/orders"));
    }

    @Test
    void v3ApiDocs_declaresBearerJwtSecurityScheme() {
        RestClient client =
                RestClient.builder().baseUrl("http://127.0.0.1:" + port).build();
        @SuppressWarnings("unchecked")
        Map<String, Object> spec = client.get().uri("/v3/api-docs").retrieve().body(Map.class);

        @SuppressWarnings("unchecked")
        Map<String, Object> components = (Map<String, Object>) spec.get("components");
        assertThat(components).isNotNull().containsKey("securitySchemes");
        @SuppressWarnings("unchecked")
        Map<String, Object> schemes = (Map<String, Object>) components.get("securitySchemes");
        assertThat(schemes).containsKey("bearer-jwt");
    }
}
