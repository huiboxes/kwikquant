package com.kwikquant;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 集成测试基类：单个共享 PostgreSQL 实例，跨所有子类复用。
 *
 * <p>数据库来源由 {@link TestDatabase#resolve()} 决定：默认 Testcontainers 容器；设置环境变量
 * {@code KQ_TEST_DB_URL} 后切换为本机原生 PostgreSQL（Docker 不可用的受限环境，详见
 * {@link ExternalTestDatabase}）。两种模式下子类均获得"每次运行全新、隔离"的库。
 *
 * <p>静态块在 Spring context 创建前完成数据库启动；{@code @DynamicPropertySource} 把连接信息注入
 * datasource。配合 Spring 的 ApplicationContext 缓存（同配置的子类共享 context），URL 全程稳定。
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    private static final TestDatabase DATABASE = TestDatabase.resolve();

    static {
        DATABASE.start();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DATABASE::jdbcUrl);
        registry.add("spring.datasource.username", DATABASE::username);
        registry.add("spring.datasource.password", DATABASE::password);
        // ENCRYPTION_KEY 默认值（32 字节 base64），让 contextLoads 等无需依赖 shell 环境变量
        registry.add("kwikquant.encryption.key", () -> "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
    }
}
