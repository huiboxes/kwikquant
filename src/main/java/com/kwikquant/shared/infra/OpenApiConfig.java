package com.kwikquant.shared.infra;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import java.util.Map;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 3 契约配置。
 *
 * <p>{@code springdoc-openapi-starter-webmvc-ui} 从 Spring MVC 注解自动生成 OpenAPI 3 spec
 * （{@code /v3/api-docs}）+ Swagger UI（{@code /swagger-ui/index.html}）。本类补：
 * <ol>
 *   <li>元信息 + JWT Bearer security scheme；</li>
 *   <li>4 个通用错误响应（401/403/400/500）注册到 {@code components.responses}；</li>
 *   <li>{@link #commonResponsesInjector} 把 4 通用响应以 {@code $ref} 注入每个 endpoint 的
 *       {@code responses}——避免 82 endpoint × 4 = 328 条噪音 {@code @ApiResponse} 注解，
 *       同时让前端 codegen 在 endpoint 级看到通用错误可能性（springdoc 默认不合并全局 responses）。</li>
 * </ol>
 *
 * <p>契约本体由各 {@code @RestController} 的 {@code @Operation}/{@code @ApiResponse} 注解驱动
 * （业务专属错误码逐 endpoint 声明，通用 4 码全局注入）。错误码 catalog 见 {@link ErrorCode}；
 * WebSocket 契约见 {@code docs/ws-contract.md}；行为契约（状态机/轮询/鉴权流程）见
 * {@code docs/behavior-contract.md}。
 */
@Configuration
public class OpenApiConfig {

    /** 通用响应 key → HTTP code。 */
    private static final Map<String, String> COMMON_RESPONSES = Map.of(
            "Unauthorized", "401",
            "Forbidden", "403",
            "ValidationError", "400",
            "InternalServerError", "500");

    @Bean
    OpenAPI kwikquantOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("KwikQuant API")
                        .version("0.0.1")
                        .description("Cryptocurrency quantitative trading backend — Spring Modulith. "
                                + "错误码 catalog 见 ErrorCode.java；WebSocket 契约见 docs/ws-contract.md；"
                                + "行为契约（状态机/轮询/鉴权流程/二次确认）见 docs/behavior-contract.md。"
                                + "所有响应为 ApiResponse envelope {code,message,data,traceId}——前端看 body.code "
                                + "判断业务结果（风控拒 HTTP 200 + code=4105 是反例，非 HTTP status）。"))
                .servers(List.of(new Server().url("/").description("local dev")))
                .components(new Components()
                        .addSecuritySchemes(
                                "bearer-jwt",
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")
                                        .description("JWT access token，Authorization: Bearer <token>"))
                        .addResponses("Unauthorized", errorResponse("401", "未认证（1001 UNAUTHENTICATED）"))
                        .addResponses("Forbidden", errorResponse("403", "越权（1002 FORBIDDEN）"))
                        .addResponses("ValidationError", errorResponse("400", "参数校验失败（3001 VALIDATION_FAILED）"))
                        .addResponses("InternalServerError", errorResponse("500", "服务端内部错误（5001 INTERNAL_ERROR）")));
    }

    /**
     * 构造通用错误响应：HTTP code + description。不带 content schema——springdoc 对泛型
     * {@code ApiResponse<T>} 不生成独立 "ApiResponse" schema（PoC 验证），通用响应靠约定：
     * 错误响应 = ApiResponse envelope {code, message, data:null, traceId}，前端从 endpoint
     * 2xx 响应的 {@code ApiResponse<XxxDto>} schema 已知 envelope 结构。
     */
    private static ApiResponse errorResponse(String code, String description) {
        return new ApiResponse().description(description);
    }

    /**
     * 把 4 个通用响应以 {@code $ref} 注入每个 operation 的 responses（若该 code 已存在则跳过——
     * 业务专属 @ApiResponse 优先）。路径 A（OperationCustomizer 风格的全局后处理）：
     * springdoc 默认不把 components.responses 合并到 endpoint.responses，需此注入。
     */
    @Bean
    OpenApiCustomizer commonResponsesInjector() {
        return openApi -> {
            if (openApi.getPaths() == null) {
                return;
            }
            for (PathItem pathItem : openApi.getPaths().values()) {
                injectIfPresent(pathItem.getGet());
                injectIfPresent(pathItem.getPost());
                injectIfPresent(pathItem.getPut());
                injectIfPresent(pathItem.getDelete());
                injectIfPresent(pathItem.getPatch());
            }
        };
    }

    private static void injectIfPresent(Operation operation) {
        if (operation == null) {
            return;
        }
        io.swagger.v3.oas.models.responses.ApiResponses responses = operation.getResponses();
        if (responses == null) {
            responses = new io.swagger.v3.oas.models.responses.ApiResponses();
            operation.setResponses(responses);
        }
        for (Map.Entry<String, String> e : COMMON_RESPONSES.entrySet()) {
            String key = e.getKey();
            String code = e.getValue();
            // 业务专属 @ApiResponse 已声明该 code → 跳过，不覆盖
            if (!responses.containsKey(code)) {
                responses.put(code, new ApiResponse().$ref("#/components/responses/" + key));
            }
        }
    }
}
