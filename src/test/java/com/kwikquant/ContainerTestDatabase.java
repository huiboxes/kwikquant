package com.kwikquant;

import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 默认策略：单个共享的 PostgreSQL Testcontainer，JVM 生命周期内复用（与原实现一致）。
 *
 * <p>不用 {@code @Testcontainers}+{@code @Container}（那会 per-subclass 重启容器、换端口），配合 Spring 的
 * ApplicationContext 缓存（同配置的子类共享 context），datasource URL 始终指向同一容器的同一端口。
 */
final class ContainerTestDatabase implements TestDatabase {

    private final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("kwikquant_test")
            .withUsername("test")
            .withPassword("test");

    @Override
    public void start() {
        postgres.start();
        Runtime.getRuntime().addShutdownHook(new Thread(postgres::stop));
    }

    @Override
    public String jdbcUrl() {
        // 用 127.0.0.1 替代 localhost：surefire JVM 在 Colima 虚拟化负载下偶发
        // UnknownHostException: localhost（macOS DNS 抖动），IP 字面量绕过 DNS 解析。
        return postgres.getJdbcUrl().replace("localhost", "127.0.0.1");
    }

    @Override
    public String username() {
        return postgres.getUsername();
    }

    @Override
    public String password() {
        return postgres.getPassword();
    }
}
