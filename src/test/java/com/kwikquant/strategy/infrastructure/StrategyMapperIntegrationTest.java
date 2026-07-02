package com.kwikquant.strategy.infrastructure;

import static org.assertj.core.api.Assertions.*;

import com.kwikquant.AbstractIntegrationTest;
import com.kwikquant.KwikquantApplication;
import com.kwikquant.shared.types.StrategyStatus;
import com.kwikquant.strategy.domain.StrategyDefinition;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 集成测试：StrategyMapper 全 SQL 路径 + StrategyDefinition 全字段往返（覆盖 setter/getter）。 */
@SpringBootTest(classes = KwikquantApplication.class)
@TestPropertySource(
        properties = {
            "JWT_SECRET=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
            "ENCRYPTION_KEY=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
        })
class StrategyMapperIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    StrategyMapper strategyMapper;

    private static long uniqueUserId() {
        return System.nanoTime() % 1_000_000L + 1_000_000L;
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
        long userId = uniqueUserId();
        StrategyDefinition s = StrategyDefinition.create(
                userId, "MA Cross", "desc", "BTC/USDT", "BINANCE", "SPOT", "1h", "{\"fast\":14}");
        strategyMapper.insert(s);
        assertThat(s.getId()).isNotNull();

        StrategyDefinition loaded = strategyMapper.findById(s.getId());
        assertThat(loaded).isNotNull();
        assertThat(loaded.getUserId()).isEqualTo(userId);
        assertThat(loaded.getName()).isEqualTo("MA Cross");
        assertThat(loaded.getDescription()).isEqualTo("desc");
        assertThat(loaded.getSymbol()).isEqualTo("BTC/USDT");
        assertThat(loaded.getExchange()).isEqualTo("BINANCE");
        assertThat(loaded.getMarketType()).isEqualTo("SPOT");
        assertThat(loaded.getIntervalValue()).isEqualTo("1h");
        assertThat(loaded.getStatus()).isEqualTo(StrategyStatus.DRAFT);
        assertThat(parseJson(loaded.getParameters())).isEqualTo(parseJson("{\"fast\":14}"));
        assertThat(loaded.isDeleted()).isFalse();
        assertThat(loaded.getCreatedAt()).isNotNull();
        assertThat(loaded.getUpdatedAt()).isNotNull();
    }

    @Test
    void findByUserId_returnsOnlyOwned() {
        long userId = uniqueUserId();
        StrategyDefinition s = StrategyDefinition.create(userId, "n", null, "BTC/USDT", "BINANCE", "SPOT", "1h", "{}");
        strategyMapper.insert(s);

        List<StrategyDefinition> owned = strategyMapper.findByUserId(userId);
        assertThat(owned).hasSize(1);
        assertThat(owned.get(0).getId()).isEqualTo(s.getId());
    }

    @Test
    void updateStatus_casSemantics() {
        long userId = uniqueUserId();
        StrategyDefinition s = StrategyDefinition.create(userId, "n", null, "BTC/USDT", "BINANCE", "SPOT", "1h", "{}");
        strategyMapper.insert(s);

        // DRAFT → READY 成功
        assertThat(strategyMapper.updateStatus(s.getId(), userId, "DRAFT", "READY"))
                .isEqualTo(1);
        // 期望 DRAFT 但实际 READY → CAS 0
        assertThat(strategyMapper.updateStatus(s.getId(), userId, "DRAFT", "RUNNING"))
                .isZero();
        // 错的 userId → 深度防御拦截，CAS 0
        assertThat(strategyMapper.updateStatus(s.getId(), userId + 999, "READY", "RUNNING"))
                .isZero();
    }

    @Test
    void update_changesFields() {
        long userId = uniqueUserId();
        StrategyDefinition s =
                StrategyDefinition.create(userId, "old", null, "BTC/USDT", "BINANCE", "SPOT", "1h", "{}");
        strategyMapper.insert(s);
        s.setName("new");
        s.setSymbol("ETH/USDT");
        s.setParameters("{\"x\":1}");
        strategyMapper.update(s);

        StrategyDefinition loaded = strategyMapper.findById(s.getId());
        assertThat(loaded.getName()).isEqualTo("new");
        assertThat(loaded.getSymbol()).isEqualTo("ETH/USDT");
        assertThat(parseJson(loaded.getParameters())).isEqualTo(parseJson("{\"x\":1}"));
    }

    @Test
    void softDelete_hidesFromFindById() {
        long userId = uniqueUserId();
        StrategyDefinition s = StrategyDefinition.create(userId, "n", null, "BTC/USDT", "BINANCE", "SPOT", "1h", "{}");
        strategyMapper.insert(s);

        // 错的 userId → 拦截
        assertThat(strategyMapper.softDelete(s.getId(), userId + 999)).isZero();
        assertThat(strategyMapper.softDelete(s.getId(), userId)).isEqualTo(1);
        assertThat(strategyMapper.findById(s.getId())).isNull();
    }

    @Test
    void findByStatus_findsRunning() {
        long userId = uniqueUserId();
        StrategyDefinition s = StrategyDefinition.create(userId, "n", null, "BTC/USDT", "BINANCE", "SPOT", "1h", "{}");
        strategyMapper.insert(s);
        strategyMapper.updateStatus(s.getId(), userId, "DRAFT", "READY");
        strategyMapper.updateStatus(s.getId(), userId, "READY", "RUNNING");

        List<StrategyDefinition> running = strategyMapper.findByStatus("RUNNING");
        assertThat(running).extracting(StrategyDefinition::getId).contains(s.getId());
    }
}
