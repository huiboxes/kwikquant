package com.kwikquant.strategy.infrastructure;

import static org.assertj.core.api.Assertions.*;

import com.kwikquant.AbstractIntegrationTest;
import com.kwikquant.KwikquantApplication;
import com.kwikquant.strategy.domain.BacktestTask;
import com.kwikquant.strategy.domain.BacktestTaskStatus;
import com.kwikquant.strategy.domain.StrategyCode;
import com.kwikquant.strategy.domain.StrategyDefinition;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 集成测试：BacktestTaskMapper SQL + BacktestTask 全字段往返。 */
@SpringBootTest(classes = KwikquantApplication.class)
@TestPropertySource(
        properties = {
            "JWT_SECRET=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
            "ENCRYPTION_KEY=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
        })
class BacktestTaskMapperIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    StrategyMapper strategyMapper;

    @Autowired
    StrategyCodeMapper codeMapper;

    @Autowired
    BacktestTaskMapper taskMapper;

    private long[] seedStrategyAndCode() {
        long userId = System.nanoTime() % 1_000_000L + 1_000_000L;
        StrategyDefinition s = StrategyDefinition.create(userId, "n", null, "BTC/USDT", "BINANCE", "SPOT", "1h", "{}");
        strategyMapper.insert(s);
        StrategyCode code = StrategyCode.create(s.getId(), 1, "def on_bar(): pass", null);
        codeMapper.insert(code);
        return new long[] {s.getId(), code.getId(), userId};
    }

    private static final ObjectMapper OM = new ObjectMapper();

    /** JSONB 往返会被 PostgreSQL 规范化（空格/键序），用语义比较而非字符串相等。 */
    private static JsonNode parseJson(String json) {
        try {
            return OM.readTree(json);
        } catch (Exception e) {
            throw new AssertionError("invalid JSON: " + json, e);
        }
    }

    @Test
    void insertAndFindById_roundTripsAllFields() {
        long[] ids = seedStrategyAndCode();
        long strategyId = ids[0], codeId = ids[1], userId = ids[2];
        BacktestTask t = BacktestTask.create(
                strategyId,
                userId,
                codeId,
                "BTC/USDT",
                "BINANCE",
                "1h",
                Instant.parse("2025-01-01T00:00:00Z"),
                Instant.parse("2025-06-01T00:00:00Z"),
                "{\"fast\":14}");
        taskMapper.insert(t);
        assertThat(t.getId()).isNotNull();

        BacktestTask loaded = taskMapper.findById(t.getId());
        assertThat(loaded).isNotNull();
        assertThat(loaded.getStrategyId()).isEqualTo(strategyId);
        assertThat(loaded.getUserId()).isEqualTo(userId);
        assertThat(loaded.getStrategyCodeId()).isEqualTo(codeId);
        assertThat(loaded.getStatus()).isEqualTo(BacktestTaskStatus.PENDING);
        assertThat(loaded.getSymbol()).isEqualTo("BTC/USDT");
        assertThat(loaded.getExchange()).isEqualTo("BINANCE");
        assertThat(loaded.getIntervalValue()).isEqualTo("1h");
        assertThat(loaded.getStartTime()).isEqualTo(Instant.parse("2025-01-01T00:00:00Z"));
        assertThat(loaded.getEndTime()).isEqualTo(Instant.parse("2025-06-01T00:00:00Z"));
        assertThat(parseJson(loaded.getParameters())).isEqualTo(parseJson("{\"fast\":14}"));
        assertThat(loaded.getResult()).isNull();
        assertThat(loaded.getErrorMessage()).isNull();
    }

    @Test
    void updateStatus_casAndTransitions() {
        long[] ids = seedStrategyAndCode();
        long strategyId = ids[0], codeId = ids[1], userId = ids[2];
        BacktestTask t = BacktestTask.create(
                strategyId, userId, codeId, "BTC/USDT", "BINANCE", "1h", Instant.now(), Instant.now(), "{}");
        taskMapper.insert(t);

        assertThat(taskMapper.updateStatus(t.getId(), userId, "PENDING", "RUNNING"))
                .isEqualTo(1);
        // 期望 PENDING 但实际 RUNNING → 0
        assertThat(taskMapper.updateStatus(t.getId(), userId, "PENDING", "RUNNING"))
                .isZero();
        // 错的 userId → 深度防御拦截
        assertThat(taskMapper.updateStatus(t.getId(), userId + 999, "RUNNING", "COMPLETED"))
                .isZero();
    }

    @Test
    void updateResult_setsCompleted() {
        long[] ids = seedStrategyAndCode();
        long userId = ids[2];
        BacktestTask t = BacktestTask.create(
                ids[0], userId, ids[1], "BTC/USDT", "BINANCE", "1h", Instant.now(), Instant.now(), "{}");
        taskMapper.insert(t);
        taskMapper.updateStatus(t.getId(), userId, "PENDING", "RUNNING");

        // 错的 userId → 拦截
        assertThat(taskMapper.updateResult(t.getId(), userId + 999, "{\"hijack\":true}", null))
                .isZero();
        assertThat(taskMapper.updateResult(t.getId(), userId, "{\"realizedPnl\":100}", null))
                .isEqualTo(1);
        BacktestTask loaded = taskMapper.findById(t.getId());
        assertThat(loaded.getStatus()).isEqualTo(BacktestTaskStatus.COMPLETED);
        assertThat(parseJson(loaded.getResult())).isEqualTo(parseJson("{\"realizedPnl\":100}"));
        assertThat(loaded.getReportId()).isNull();
    }

    @Test
    void updateError_setsFailed() {
        long[] ids = seedStrategyAndCode();
        long userId = ids[2];
        BacktestTask t = BacktestTask.create(
                ids[0], userId, ids[1], "BTC/USDT", "BINANCE", "1h", Instant.now(), Instant.now(), "{}");
        taskMapper.insert(t);
        taskMapper.updateStatus(t.getId(), userId, "PENDING", "RUNNING");

        // 错的 userId → 拦截
        assertThat(taskMapper.updateError(t.getId(), userId + 999, "hijack")).isZero();
        assertThat(taskMapper.updateError(t.getId(), userId, "boom")).isEqualTo(1);
        BacktestTask loaded = taskMapper.findById(t.getId());
        assertThat(loaded.getStatus()).isEqualTo(BacktestTaskStatus.FAILED);
        assertThat(loaded.getErrorMessage()).isEqualTo("boom");
    }

    @Test
    void findByStrategyIdAndUserId() {
        long[] ids = seedStrategyAndCode();
        BacktestTask t = BacktestTask.create(
                ids[0], ids[2], ids[1], "BTC/USDT", "BINANCE", "1h", Instant.now(), Instant.now(), "{}");
        taskMapper.insert(t);

        assertThat(taskMapper.findByStrategyId(ids[0])).hasSize(1);
        assertThat(taskMapper.findByUserId(ids[2])).hasSize(1);
    }
}
