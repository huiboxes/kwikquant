package com.kwikquant;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 集成测试基类：单个共享 PostgreSQL Testcontainers 实例，跨所有子类复用。
 *
 * <p>不用 {@code @Testcontainers}+{@code @Container}（那会 per-subclass 重启容器、换端口），
 * 而是 {@code static} 块手动 start 一次，JVM 生命周期内共享。配合 Spring 的 ApplicationContext 缓存
 * （同配置的子类共享 context），datasource URL 始终指向同一容器的同一端口。
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("kwikquant_test")
            .withUsername("test")
            .withPassword("test");

    static {
        POSTGRES.start();
        Runtime.getRuntime().addShutdownHook(new Thread(POSTGRES::stop));
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        // 用 127.0.0.1 替代 localhost：surefire JVM 在 Colima 虚拟化负载下偶发
        // UnknownHostException: localhost（macOS DNS 抖动），IP 字面量绕过 DNS 解析。
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl().replace("localhost", "127.0.0.1"));
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // ENCRYPTION_KEY 默认值（32 字节 base64），让 contextLoads 等无需依赖 shell 环境变量
        registry.add("kwikquant.encryption.key", () -> "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
    }
}
