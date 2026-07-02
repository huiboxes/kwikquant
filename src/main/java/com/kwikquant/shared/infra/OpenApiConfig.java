package com.kwikquant.shared.infra;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 3 契约配置（Wave 8 前端契约冻结）。
 *
 * <p>{@code springdoc-openapi-starter-webmvc-ui} 从 Spring MVC 注解自动生成 OpenAPI 3 spec
 * （{@code /v3/api-docs}）+ Swagger UI（{@code /swagger-ui/index.html}）。本类只补元信息 +
 * JWT Bearer security scheme——契约本体由各 {@code @RestController} 的注解驱动。
 *
 * <p>前端（Wave 9 Dashboard）据 {@code /v3/api-docs} 生成 typed client（openapi-typescript 或
 * axios+zod）；WebSocket 主题契约另出 markdown 文档（OpenAPI 不善 WS，AsyncAPI 过重）。错误码
 * catalog 见 {@link ErrorCode}，回测结果格式见 product-direction §8。
 */
@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI kwikquantOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("KwikQuant API")
                        .version("0.0.1")
                        .description("Cryptocurrency quantitative trading backend — Spring Modulith"))
                .servers(List.of(new Server().url("/").description("local dev")))
                .components(new Components()
                        .addSecuritySchemes(
                                "bearer-jwt",
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")
                                        .description("JWT access token，Authorization: Bearer <token>")));
    }
}
