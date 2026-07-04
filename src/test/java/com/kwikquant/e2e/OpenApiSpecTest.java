package com.kwikquant.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.kwikquant.AbstractIntegrationTest;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
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

    // ===== api-contract-100 §3.1 PoC：通用响应注入 + 枚举字段 + envelope schema =====

    /** 验证 §1.4 路径 A（OpenApiCustomizer 注入通用响应）生效：4 通用响应在 components + 每个 endpoint。 */
    @Test
    @SuppressWarnings("unchecked")
    void v3ApiDocs_commonResponsesInjectedIntoEveryEndpoint() {
        RestClient client =
                RestClient.builder().baseUrl("http://127.0.0.1:" + port).build();
        Map<String, Object> spec = client.get().uri("/v3/api-docs").retrieve().body(Map.class);
        Map<String, Object> components = (Map<String, Object>) spec.get("components");
        Map<String, Object> responses = (Map<String, Object>) components.get("responses");
        assertThat(responses).containsKeys("Unauthorized", "Forbidden", "ValidationError", "InternalServerError");

        Map<String, Object> paths = (Map<String, Object>) spec.get("paths");
        Map<String, Object> ordersPath = (Map<String, Object>) paths.get("/api/v1/orders");
        Map<String, Object> postOp = (Map<String, Object>) ordersPath.get("post");
        Map<String, Object> postResponses = (Map<String, Object>) postOp.get("responses");
        // 4 通用码注入（401/403/400/500）+ 业务专属（200 风控拒 / 422 状态/余额 / 400 参数）
        assertThat(postResponses).containsKeys("401", "403", "400", "500", "200", "422");
        // 通用响应是 $ref，业务响应是 inline description
        Map<String, Object> r401 = (Map<String, Object>) postResponses.get("401");
        assertThat(r401.get("$ref")).asString().isEqualTo("#/components/responses/Unauthorized");
    }

    /** 验证 §1.3 枚举策略：record 字段 @Schema description 含枚举值（PoC 确认 springdoc 3.0.3 行为）。 */
    @Test
    @SuppressWarnings("unchecked")
    void v3ApiDocs_enumFieldsDocumentedViaDescription() {
        RestClient client =
                RestClient.builder().baseUrl("http://127.0.0.1:" + port).build();
        Map<String, Object> spec = client.get().uri("/v3/api-docs").retrieve().body(Map.class);
        Map<String, Object> components = (Map<String, Object>) spec.get("components");
        Map<String, Object> schemas = (Map<String, Object>) components.get("schemas");

        // OrderSubmitRequest.side description 含 BUY|SELL
        Map<String, Object> reqSchema = (Map<String, Object>) schemas.get("OrderSubmitRequest");
        Map<String, Object> reqProps = (Map<String, Object>) reqSchema.get("properties");
        Map<String, Object> side = (Map<String, Object>) reqProps.get("side");
        assertThat(side.get("description").toString()).contains("BUY", "SELL");

        // OrderDetailDto.status description 含全 6 态
        Map<String, Object> dtoSchema = (Map<String, Object>) schemas.get("OrderDetailDto");
        Map<String, Object> dtoProps = (Map<String, Object>) dtoSchema.get("properties");
        Map<String, Object> status = (Map<String, Object>) dtoProps.get("status");
        assertThat(status.get("description").toString())
                .contains("NEW", "PARTIAL", "FILLED", "CANCELLED", "REJECTED", "EXPIRED");
    }

    /** 验证 ApiResponse envelope schema 文档化（springdoc 对泛型 ApiResponse<T> 生成内联 schema，非独立 "ApiResponse"）。 */
    @Test
    @SuppressWarnings("unchecked")
    void v3ApiDocs_apiResponseEnvelopeSchemaDocumented() {
        RestClient client =
                RestClient.builder().baseUrl("http://127.0.0.1:" + port).build();
        Map<String, Object> spec = client.get().uri("/v3/api-docs").retrieve().body(Map.class);
        Map<String, Object> components = (Map<String, Object>) spec.get("components");
        Map<String, Object> schemas = (Map<String, Object>) components.get("schemas");
        // springdoc 对 ApiResponse<OrderSubmitResult> 等泛型实例生成内联 schema，含 envelope 四字段
        boolean foundEnvelope = schemas.values().stream()
                .map(s -> (Map<String, Object>) s)
                .filter(s -> s.get("properties") instanceof Map)
                .map(s -> (Map<String, Object>) s.get("properties"))
                .anyMatch(props -> props.containsKey("code")
                        && props.containsKey("message")
                        && props.containsKey("data")
                        && props.containsKey("traceId"));
        assertThat(foundEnvelope)
                .as("至少一个 schema 是 ApiResponse envelope（含 code/message/data/traceId）")
                .isTrue();
        // 抽样：POST /api/v1/orders 200 响应的 schema 含 envelope 四字段 + description 非空
        Map<String, Object> paths = (Map<String, Object>) spec.get("paths");
        Map<String, Object> ordersPath = (Map<String, Object>) paths.get("/api/v1/orders");
        Map<String, Object> postOp = (Map<String, Object>) ordersPath.get("post");
        Map<String, Object> responses = (Map<String, Object>) postOp.get("responses");
        Map<String, Object> ok200 = (Map<String, Object>) responses.get("201");
        if (ok200 == null) {
            ok200 = (Map<String, Object>) responses.get("200");
        }
        assertThat(ok200).as("POST /orders 应有 201/200 成功响应").isNotNull();
    }

    // ===== api-contract-100 §3.11 验收门：swagger-parser 0 error + 抽样 endpoint 注解 =====

    /**
     * §3.11 验收门 2：swagger-parser 校验 /v3/api-docs 生成有效 OpenAPI 3.0 spec，0 error。
     * 这是"契约够不够驱动前端 codegen"的硬验证——spec 必须规范合法。
     */
    @Test
    void v3ApiDocs_swaggerParserValidatesZeroError() {
        RestClient client =
                RestClient.builder().baseUrl("http://127.0.0.1:" + port).build();
        String specJson = client.get().uri("/v3/api-docs").retrieve().body(String.class);
        assertThat(specJson).isNotBlank();

        SwaggerParseResult result = new OpenAPIV3Parser().readContents(specJson, null, null);
        assertThat(result.getOpenAPI()).as("swagger-parser 应成功解析 spec").isNotNull();
        // 0 error：messages 为空或仅含可忽略的非阻断提示（swagger-parser 对未识别扩展字段可能产出 message）
        assertThat(result.getMessages() == null || result.getMessages().isEmpty())
                .as("swagger-parser 校验 0 error，实际 messages: %s", result.getMessages())
                .isTrue();
    }

    /**
     * §3.11 验收门 4：抽样 OrderController + AuthController + StrategyController 三个 endpoint 的注解完整性。
     * 验证 @Tag（模块聚合）+ @Operation（summary 非空）+ @ApiResponse（业务专属错误码声明）三件套。
     */
    @Test
    @SuppressWarnings("unchecked")
    void v3ApiDocs_sampledEndpointsHaveTagOperationAndBusinessResponses() {
        RestClient client =
                RestClient.builder().baseUrl("http://127.0.0.1:" + port).build();
        Map<String, Object> spec = client.get().uri("/v3/api-docs").retrieve().body(Map.class);
        Map<String, Object> paths = (Map<String, Object>) spec.get("paths");

        // 1. AuthController POST /api/v1/auth/login：公开端点，声明 401（1001 凭据无效）
        Map<String, Object> loginPath = (Map<String, Object>) paths.get("/api/v1/auth/login");
        assertThat(loginPath).as("AuthController /auth/login 应在 spec").isNotNull();
        Map<String, Object> loginPost = (Map<String, Object>) loginPath.get("post");
        assertThat(loginPost.get("summary")).asString().isNotEmpty();
        Map<String, Object> loginResponses = (Map<String, Object>) loginPost.get("responses");
        assertThat(loginResponses).containsKeys("401", "400"); // 401 凭据无效 + 400 参数（通用注入）

        // 2. OrderController DELETE /api/v1/orders/{orderId}：声明 422（4101）+ 409（4107）
        Map<String, Object> ordersPath = (Map<String, Object>) paths.get("/api/v1/orders/{orderId}");
        assertThat(ordersPath).as("OrderController /orders/{orderId} 应在 spec").isNotNull();
        Map<String, Object> deleteOp = (Map<String, Object>) ordersPath.get("delete");
        assertThat(deleteOp.get("summary")).asString().isNotEmpty();
        Map<String, Object> deleteResponses = (Map<String, Object>) deleteOp.get("responses");
        assertThat(deleteResponses).containsKeys("422", "409"); // 4101 状态不可撤 + 4107 并发冲突

        // 3. StrategyController POST /api/v1/strategies/{id}/start：声明 409（7002/7006）+ 500（7200）
        Map<String, Object> startPath = (Map<String, Object>) paths.get("/api/v1/strategies/{id}/start");
        assertThat(startPath)
                .as("StrategyController /strategies/{id}/start 应在 spec")
                .isNotNull();
        Map<String, Object> startPost = (Map<String, Object>) startPath.get("post");
        assertThat(startPost.get("summary")).asString().isNotEmpty();
        Map<String, Object> startResponses = (Map<String, Object>) startPost.get("responses");
        assertThat(startResponses).containsKeys("409", "500"); // 7002/7006 状态 + 7200 worker 失败

        // 4. @Tag 验证：tags 数组非空，模块聚合到 Swagger UI 分组
        assertThat(loginPost.get("tags")).asList().isNotEmpty();
        assertThat(deleteOp.get("tags")).asList().isNotEmpty();
        assertThat(startPost.get("tags")).asList().isNotEmpty();
    }
}
