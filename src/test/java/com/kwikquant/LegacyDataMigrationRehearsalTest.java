package com.kwikquant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 存量数据迁移演练:V59(资金费期次化)与 V62(schema 硬化 + legacy 桶身份回填)的
 * 数据形态改动此前只有 fresh-schema 集成测试覆盖——空库全链跑通,回填 / 去重归档 /
 * 冲突守卫这些 UPDATE/DELETE 分支从未在"旧世界行"上演练过。本测试用 Flyway target
 * 分段:先迁到 V58(旧世界末版),灌入墙钟语义的存量行,再迁到 V62,逐项断言落点。
 *
 * <p>走独立 {@link TestDatabase}(外部模式=全新随机 schema,容器模式=独立容器),
 * 与 {@link AbstractIntegrationTest} 的 Spring 上下文库互不干扰;不起 Spring,纯 JDBC + Flyway。
 */
class LegacyDataMigrationRehearsalTest {

    private static TestDatabase database;
    private static String url;

    @BeforeAll
    static void migrateOldWorldSeedAndUpgrade() throws SQLException {
        database = TestDatabase.resolve();
        database.start();
        url = database.jdbcUrl();
        flywayTo("58");
        seedOldWorld();
        flywayTo("62");
    }

    private static void flywayTo(String target) {
        Flyway.configure()
                .dataSource(url, database.username(), database.password())
                .locations("classpath:db/migration")
                .target(target)
                .load()
                .migrate();
    }

    /** 旧世界(≤V58)存量形态:墙钟毫秒幂等键双扣行、null bill_id 行、legacy 净持仓 NULL 桶身份行。 */
    private static void seedOldWorld() throws SQLException {
        try (Connection c = open();
                Statement s = c.createStatement()) {
            // funding_settlements(V43 形态,尚无 funding_time/rate_kind):
            // A/B/B2 同账户+持仓+同 8h 网格的墙钟三扣行(幂等键差 1 分钟)→ V59 去重保 A 归档 B/B2。
            // 种 3 行(而非 2 行)为行使归档 SELECT DISTINCT 分支:≥3 重复行时 join 为同一 f
            // 产出多份副本(B2 配 A、B 两个 keep),无 DISTINCT 归档会多算行数
            s.execute("INSERT INTO funding_settlements (account_id, position_id, symbol, funding_rate,"
                    + " qty_at_settle, funding_amount, settle_time, bill_id)"
                    + " VALUES (1, 10, 'ETH/USDT:USDT', 0.0001, 1, -8, '2025-01-01 07:30:00', 'PAPER-1')");
            s.execute("INSERT INTO funding_settlements (account_id, position_id, symbol, funding_rate,"
                    + " qty_at_settle, funding_amount, settle_time, bill_id)"
                    + " VALUES (1, 10, 'ETH/USDT:USDT', 0.0001, 1, -8, '2025-01-01 07:31:00', 'PAPER-2')");
            s.execute("INSERT INTO funding_settlements (account_id, position_id, symbol, funding_rate,"
                    + " qty_at_settle, funding_amount, settle_time, bill_id)"
                    + " VALUES (1, 10, 'ETH/USDT:USDT', 0.0001, 1, -8, '2025-01-01 07:32:00', 'PAPER-3')");
            // null bill_id 行(旧幂等键 UNIQUE(account_id, bill_id) 对 null 零防护)→ V62 回填 LEGACY-{id}
            s.execute("INSERT INTO funding_settlements (account_id, position_id, symbol, funding_rate,"
                    + " qty_at_settle, funding_amount, settle_time, bill_id)"
                    + " VALUES (1, 11, 'ETH/USDT:USDT', NULL, 2, 5, '2025-01-02 16:00:00', NULL)");
            // LIVE 行:position_id NULL 不参与 V59 去重(双向持仓同期两条 null 行合法)
            s.execute("INSERT INTO funding_settlements (account_id, position_id, symbol, funding_rate,"
                    + " qty_at_settle, funding_amount, settle_time, bill_id)"
                    + " VALUES (1, NULL, 'BTC/USDT:USDT', NULL, 1, -3, '2025-01-01 08:00:00', 'OKX-1')");
            // positions legacy 形态(V31 净持仓时代 position_side NULL 而 side 有值):
            // P1 无冲突 → V62 回填 LONG
            s.execute(
                    "INSERT INTO positions (account_id, symbol, side, qty, avg_entry_price, leverage, margin_mode, position_side)"
                            + " VALUES (1, 'BTC/USDT:USDT', 'long', 0.1, 42000, 10, 'ISOLATED', NULL)");
            // P2 正规 SHORT 桶行(已全平,qty=0)
            s.execute(
                    "INSERT INTO positions (account_id, symbol, side, qty, avg_entry_price, leverage, margin_mode, position_side)"
                            + " VALUES (1, 'ETH/USDT:USDT', 'short', 0, 3000, 10, 'ISOLATED', 'SHORT')");
            // P3 legacy SHORT:与 P2 同桶 → NOT EXISTS 守卫跳过(无守卫会撞 V38 COALESCE 键使迁移硬失败),留 NULL
            s.execute(
                    "INSERT INTO positions (account_id, symbol, side, qty, avg_entry_price, leverage, margin_mode, position_side)"
                            + " VALUES (1, 'ETH/USDT:USDT', 'short', 0.5, 3100, 10, 'ISOLATED', NULL)");
            // P4 SPOT 行(margin_mode NULL)→ 不参与回填
            s.execute(
                    "INSERT INTO positions (account_id, symbol, side, qty, avg_entry_price, leverage, margin_mode, position_side)"
                            + " VALUES (2, 'SOL/USDT', 'long', 2, 150, NULL, NULL, NULL)");
            // P5 脏行(side 不在 long/short 值域)→ 留 NULL,走消费方 warn+manual-review 通道
            s.execute("INSERT INTO positions (account_id, symbol, side, qty, leverage, margin_mode, position_side)"
                    + " VALUES (3, 'DOGE/USDT', 'flat', 0, 5, 'CROSS', NULL)");
        }
    }

    @Test
    void v59_backfillsFundingTimeOnGrid_andRateKindByBillPrefix() throws SQLException {
        // 墙钟 07:30 → 8h 网格截断到 00:00;PAPER- 前缀 → PREDICTED
        assertThat(queryString(
                        "SELECT to_char(funding_time AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS') || '|' || rate_kind"
                                + " FROM funding_settlements WHERE bill_id = 'PAPER-1'"))
                .isEqualTo("2025-01-01 00:00:00|PREDICTED");
        // 整点 LIVE 行原样落网格;非 PAPER 前缀 → SETTLED
        assertThat(queryString(
                        "SELECT to_char(funding_time AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS') || '|' || rate_kind"
                                + " FROM funding_settlements WHERE bill_id = 'OKX-1'"))
                .isEqualTo("2025-01-01 08:00:00|SETTLED");
    }

    @Test
    void v59_dedupesWallClockDuplicates_archivingDeletedRows() throws SQLException {
        // 三扣行删二保一,被删行先整行归档(旧墙钟幂等键多扣的钱已扣过,DELETE 直接销毁对账证据)
        assertThat(queryLong("SELECT count(*) FROM funding_settlements WHERE bill_id = 'PAPER-2'"))
                .isZero();
        assertThat(queryLong("SELECT count(*) FROM funding_settlements WHERE bill_id = 'PAPER-3'"))
                .isZero();
        // DISTINCT 分支:PAPER-3 在 join 中配 PAPER-1/PAPER-2 两个 keep 产出 2 份副本,归档去重后
        // 仍是每被删行恰 1 条(无 DISTINCT 时归档会是 3 行,按行差额补偿将多算)
        assertThat(queryLong("SELECT count(*) FROM funding_settlements_dedup_archive"))
                .isEqualTo(2);
        assertThat(queryLong("SELECT count(*) FROM funding_settlements_dedup_archive"
                        + " WHERE account_id = 1 AND position_id = 10 AND bill_id IN ('PAPER-2', 'PAPER-3')"))
                .isEqualTo(2);
        // 保留行(最早一条)不受影响
        assertThat(queryLong("SELECT count(*) FROM funding_settlements WHERE bill_id = 'PAPER-1'"))
                .isEqualTo(1);
    }

    @Test
    void v62_backfillsNullBillIdAsLegacy_andNullRowsEliminated() throws SQLException {
        assertThat(queryLong("SELECT count(*) FROM funding_settlements"
                        + " WHERE account_id = 1 AND position_id = 11 AND bill_id = 'LEGACY-' || id"))
                .isEqualTo(1);
        assertThat(queryLong("SELECT count(*) FROM funding_settlements WHERE bill_id IS NULL"))
                .isZero();
        // null bill_id 在 V59 的 CASE 落 else 分支 → SETTLED(非 PAPER- 前缀)
        assertThat(queryString("SELECT rate_kind FROM funding_settlements WHERE position_id = 11"))
                .isEqualTo("SETTLED");
    }

    @Test
    void v62_backfillsLegacyPositionSide_withCollisionGuard() throws SQLException {
        // P1 无冲突 → 回填 LONG
        assertThat(queryString("SELECT position_side FROM positions WHERE symbol = 'BTC/USDT:USDT'"))
                .isEqualTo("LONG");
        // P2 正规 SHORT 行不动
        assertThat(queryString("SELECT position_side FROM positions WHERE symbol = 'ETH/USDT:USDT' AND qty = 0"))
                .isEqualTo("SHORT");
        // P3 同桶已有正规 SHORT 行 → 守卫跳过留 NULL(回填会撞 V38 COALESCE 折叠键)
        assertThat(queryString("SELECT coalesce(position_side, '<NULL>') FROM positions"
                        + " WHERE symbol = 'ETH/USDT:USDT' AND qty = 0.5"))
                .isEqualTo("<NULL>");
        // P4 SPOT 行(margin_mode NULL)不参与回填
        assertThat(queryString("SELECT coalesce(position_side, '<NULL>') FROM positions WHERE symbol = 'SOL/USDT'"))
                .isEqualTo("<NULL>");
        // P5 脏 side 留 NULL
        assertThat(queryString("SELECT coalesce(position_side, '<NULL>') FROM positions WHERE symbol = 'DOGE/USDT'"))
                .isEqualTo("<NULL>");
    }

    @Test
    void v59v62_constraints_rejectIllegalWrites() throws SQLException {
        // V59 期次幂等键:同账户+持仓+期次重复写入被拒(墙钟时代的双扣形态从此进不来)
        assertRejected(
                "INSERT INTO funding_settlements (account_id, position_id, symbol, qty_at_settle,"
                        + " funding_amount, settle_time, bill_id, funding_time, rate_kind)"
                        + " VALUES (1, 10, 'ETH/USDT:USDT', 1, -8, '2025-01-01 00:00:00', 'DUP-1',"
                        + " '2025-01-01 00:00:00+00', 'SETTLED')",
                "uq_funding_settlements_period");
        // V62 rate_kind CHECK 兜底(防裸 SQL/ORM bug 绕过应用层写非法值)
        assertRejected(
                "INSERT INTO funding_settlements (account_id, position_id, symbol, qty_at_settle,"
                        + " funding_amount, settle_time, bill_id, funding_time, rate_kind)"
                        + " VALUES (999, NULL, 'ETH/USDT:USDT', 1, -8, '2025-01-01 00:00:00', 'X-1',"
                        + " '2025-01-01 00:00:00+00', 'BOGUS')",
                "chk_funding_settlements_rate_kind");
        // V62 bill_id NOT NULL 收紧(回填后 null 行绝迹,新 null 写入被拒)
        assertRejected(
                "INSERT INTO funding_settlements (account_id, position_id, symbol, qty_at_settle,"
                        + " funding_amount, settle_time, bill_id, funding_time, rate_kind)"
                        + " VALUES (999, NULL, 'ETH/USDT:USDT', 1, -8, '2025-01-03 00:00:00', NULL,"
                        + " '2025-01-03 00:00:00+00', 'SETTLED')",
                "bill_id");
        // V62 funding_rates.source CHECK(跨所代理标记枚举兜底)
        assertRejected(
                "INSERT INTO funding_rates (exchange, symbol, funding_time, source)"
                        + " VALUES ('OKX', 'BTC/USDT:USDT', '2025-01-01 00:00:00+00', 'BOGUS')",
                "chk_funding_rates_source");
        // 同族的 backtest_reports.market_type / trade_records.position_effect CHECK 不演练:
        // 播种需先造 backtest_reports 全行链(trade_records 有 FK),成本与增量信心不成比例
    }

    private static Connection open() throws SQLException {
        return DriverManager.getConnection(url, database.username(), database.password());
    }

    private static String queryString(String sql) throws SQLException {
        try (Connection c = open();
                Statement s = c.createStatement();
                ResultSet rs = s.executeQuery(sql)) {
            assertThat(rs.next()).as("单行查询应有结果: %s", sql).isTrue();
            return rs.getString(1);
        }
    }

    private static long queryLong(String sql) throws SQLException {
        try (Connection c = open();
                Statement s = c.createStatement();
                ResultSet rs = s.executeQuery(sql)) {
            assertThat(rs.next()).as("单行查询应有结果: %s", sql).isTrue();
            return rs.getLong(1);
        }
    }

    /** 执行必然违反约束的 SQL:必须抛 SQLException 且报错文本含指定片段(PG 报错带约束/列名)。 */
    private static void assertRejected(String sql, String expectedMessageFragment) {
        assertThatThrownBy(() -> {
                    try (Connection c = open();
                            Statement s = c.createStatement()) {
                        s.execute(sql);
                    }
                })
                .as("SQL 应被约束拒绝: %s", sql)
                .isInstanceOf(SQLException.class)
                .hasMessageContaining(expectedMessageFragment);
    }
}
