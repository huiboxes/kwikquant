package com.kwikquant.shared.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.env.MockPropertySource;

class ProductionConfigurationTest {

    @Test
    void baseConfigurationDoesNotDefaultToDev() throws IOException {
        MockEnvironment environment = environmentWith("application.yaml");

        assertThat(environment.getProperty("spring.profiles.default")).isNull();
        assertThat(environment.getProperty("spring.flyway.baseline-on-migrate", Boolean.class))
                .isFalse();
    }

    @Test
    void productionConfigurationUsesSecureSettingsAndGhcrWorkerImage() throws IOException {
        MockEnvironment environment = environmentWith("application-prod.yaml", "application.yaml");
        environment
                .getPropertySources()
                .addFirst(new MockPropertySource()
                        .withProperty("KWIKQUANT_MCP_PEPPER", "production-pepper")
                        .withProperty("KWIKQUANT_WORKER_IMAGE", "ghcr.io/huiboxes/kwikquant-worker:v1.2.3"));

        assertThat(environment.getProperty("kwikquant.cookie.secure", Boolean.class))
                .isTrue();
        assertThat(environment.getProperty("spring.flyway.baseline-on-migrate", Boolean.class))
                .isFalse();
        // runner 容器回访 app 的 REST 地址固定走 worker 网络容器名
        assertThat(environment.getProperty("kwikquant.worker.api-base-url")).isEqualTo("http://kwikquant-app:8080");
        // worker 镜像经 env 覆盖锁 tag(deploy 脚本 export,防 :latest 错版)
        assertThat(environment.getProperty("kwikquant.worker.image"))
                .isEqualTo("ghcr.io/huiboxes/kwikquant-worker:v1.2.3");
        // MCP pepper 经 env 注入(McpTokenHasher @Component 启动必需)
        assertThat(environment.getProperty("kwikquant.mcp.pepper")).isEqualTo("production-pepper");
    }

    @Test
    void productionWorkerImageFallsBackToGhcrLatestOnlyWhenEnvAbsent() throws IOException {
        MockEnvironment environment = environmentWith("application-prod.yaml", "application.yaml");

        assertThat(environment.getProperty("kwikquant.worker.image"))
                .isEqualTo("ghcr.io/huiboxes/kwikquant-worker:latest");
    }

    @Test
    void productionRequiresMcpPepperEnv() throws IOException {
        MockEnvironment environment = environmentWith("application-prod.yaml", "application.yaml");

        assertThatThrownBy(() -> environment.getProperty("kwikquant.mcp.pepper"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("KWIKQUANT_MCP_PEPPER");
    }

    private static MockEnvironment environmentWith(String... resources) throws IOException {
        MockEnvironment environment = new MockEnvironment();
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        for (String resource : resources) {
            for (PropertySource<?> propertySource : loader.load(resource, new ClassPathResource(resource))) {
                environment.getPropertySources().addLast(propertySource);
            }
        }
        return environment;
    }
}
