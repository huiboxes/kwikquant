package com.kwikquant.shared.infra;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

/**
 * dev profile 配置守护：dev 默认走 docker runner（与 prod 一致），不再依赖宿主系统 python3
 * 建虚拟环境。worker 镜像本地构建、api-base-url 走 host.docker.internal 回连本机 app。
 */
class DevConfigurationTest {

    @Test
    void devDefaultsToDockerRunnerAndLocalWorkerImage() throws IOException {
        MockEnvironment environment = environmentWith("application-dev.yaml", "application.yaml");

        // dev 与 prod 同口径用隔离容器跑回测，镜像自带 python3.11，绕开宿主 python 版本问题
        assertThat(environment.getProperty("kwikquant.backtest.runner")).isEqualTo("docker");
        // 本地构建的 worker 镜像（docker build -f docker/kwikquant-worker.Dockerfile）
        assertThat(environment.getProperty("kwikquant.worker.image")).isEqualTo("kwikquant-worker:latest");
        // 容器经 host.docker.internal 回连本机 app（dev app 跑在宿主 8080）
        assertThat(environment.getProperty("kwikquant.worker.api-base-url"))
                .isEqualTo("http://host.docker.internal:8080");
        // docker runner 不消费 subprocess 专属配置，dev 不再保留以免误导
        assertThat(environment.getProperty("kwikquant.worker.python-command")).isNull();
        assertThat(environment.getProperty("kwikquant.worker.script")).isNull();
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
