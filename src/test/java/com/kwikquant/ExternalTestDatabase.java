package com.kwikquant;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 外部数据库策略（Docker 不可用的受限环境）：连接 {@code KQ_TEST_DB_URL} 指向的本机原生 PostgreSQL。
 *
 * <p>隔离性设计：Testcontainers 每次运行给出全新空库，外部库是持久化的，因此每次运行在库内
 * <b>创建一个全新随机 schema</b>（{@code kq_test_<yyyyMMdd>_<hex>}）并通过 JDBC URL 的
 * {@code currentSchema} 参数把整条链路（Hikari → Flyway → MyBatis）钉在该 schema 上——
 * Flyway 以连接 search_path 的第一个 schema 为默认 schema，全部无 schema 限定的迁移对象与
 * flyway_schema_history 都落入其中，语义等价于全新空库，且支持并行多跑互不冲突。
 *
 * <p>清理：进程退出 shutdown hook 删除本次 schema（尽力而为）；启动时顺带删除日期早于今天的
 * 历史残留 schema（名称内嵌日期，误杀不可能——只删严格更早的日期）。
 */
final class ExternalTestDatabase implements TestDatabase {

    static final String SCHEMA_PREFIX = "kq_test_";
    private static final int DATE_PART_LENGTH = "yyyyMMdd".length();

    private final String baseUrl;
    private final String user;
    private final String password;
    private String schema;
    private String url;

    ExternalTestDatabase(String baseUrl, String user, String password) {
        this.baseUrl = baseUrl;
        this.user = user;
        this.password = password;
    }

    @Override
    public void start() {
        try (Connection connection = DriverManager.getConnection(baseUrl, user, password)) {
            cleanupStaleSchemas(connection);
            this.schema = SCHEMA_PREFIX
                    + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "_"
                    + Integer.toHexString(ThreadLocalRandom.current().nextInt(0x10000));
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE SCHEMA " + schema);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "外部测试数据库初始化失败（KQ_TEST_DB_URL=" + baseUrl
                            + "）：请确认 PostgreSQL 已启动且角色/库已创建（scripts/setup-local-postgres.sh）",
                    e);
        }
        this.url = baseUrl + (baseUrl.contains("?") ? "&" : "?") + "currentSchema=" + schema;
        Runtime.getRuntime().addShutdownHook(new Thread(this::dropOwnSchema));
    }

    @Override
    public String jdbcUrl() {
        return url;
    }

    @Override
    public String username() {
        return user;
    }

    @Override
    public String password() {
        return password;
    }

    /** 删除日期早于今天的 {@code kq_test_%} 残留 schema（进程被 kill -9 时 shutdown hook 不会执行）。 */
    private void cleanupStaleSchemas(Connection connection) throws SQLException {
        List<String> stale = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT schema_name FROM information_schema.schemata WHERE schema_name LIKE ?")) {
            ps.setString(1, SCHEMA_PREFIX + "%");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString(1);
                    LocalDate created = parseEmbeddedDate(name);
                    if (created != null && created.isBefore(LocalDate.now())) {
                        stale.add(name);
                    }
                }
            }
        }
        try (Statement statement = connection.createStatement()) {
            for (String name : stale) {
                statement.execute("DROP SCHEMA " + name + " CASCADE");
            }
        }
    }

    private void dropOwnSchema() {
        if (schema == null) {
            return;
        }
        try (Connection connection = DriverManager.getConnection(baseUrl, user, password);
                Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA " + schema + " CASCADE");
        } catch (SQLException ignored) {
            // 尽力清理：失败仅留下残留 schema，下次运行启动时会按日期清掉
        }
    }

    /** 从 {@code kq_test_<yyyyMMdd>_<hex>} 解析内嵌日期；格式不符返回 null（不动它）。 */
    private static LocalDate parseEmbeddedDate(String schemaName) {
        int dateStart = SCHEMA_PREFIX.length();
        int dateEnd = dateStart + DATE_PART_LENGTH;
        if (schemaName.length() < dateEnd + 1 || schemaName.charAt(dateEnd) != '_') {
            return null;
        }
        try {
            return LocalDate.parse(schemaName.substring(dateStart, dateEnd), DateTimeFormatter.BASIC_ISO_DATE);
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
