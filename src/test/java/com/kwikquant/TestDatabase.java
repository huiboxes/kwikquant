package com.kwikquant;

/**
 * 集成测试数据库抽象：{@link AbstractIntegrationTest} 通过它获得"每次运行全新、隔离的 PostgreSQL"。
 *
 * <p>两种策略，由环境变量 {@code KQ_TEST_DB_URL} 决定：
 *
 * <ul>
 *   <li><b>未设置（默认）</b>→ Testcontainers 启动 {@code postgres:16-alpine} 容器，行为与原实现完全一致；
 *   <li><b>已设置</b>→ 外部数据库模式：直接连接该 JDBC URL（本机原生 PostgreSQL），每次运行在其中创建
 *       一个全新随机 schema 实现与"全新容器"等价的隔离。适用于 Docker 不可用的受限环境（沙箱 cgroup
 *       只读等）。
 * </ul>
 */
interface TestDatabase {

    /** 启动数据库资源（容器 start 或外部库 schema 创建），进程内仅调用一次。 */
    void start();

    /** 供 Spring DataSource 使用的 JDBC URL（已含隔离所需的定位参数）。 */
    String jdbcUrl();

    String username();

    String password();

    static TestDatabase resolve() {
        String externalUrl = System.getenv("KQ_TEST_DB_URL");
        if (externalUrl != null && !externalUrl.isBlank()) {
            return new ExternalTestDatabase(
                    externalUrl.strip(),
                    envOrDefault("KQ_TEST_DB_USERNAME", "test"),
                    envOrDefault("KQ_TEST_DB_PASSWORD", "test"));
        }
        return new ContainerTestDatabase();
    }

    private static String envOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
