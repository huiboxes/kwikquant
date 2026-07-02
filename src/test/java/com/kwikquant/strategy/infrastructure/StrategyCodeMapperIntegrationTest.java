package com.kwikquant.strategy.infrastructure;

import static org.assertj.core.api.Assertions.*;

import com.kwikquant.AbstractIntegrationTest;
import com.kwikquant.KwikquantApplication;
import com.kwikquant.strategy.domain.StrategyCode;
import com.kwikquant.strategy.domain.StrategyCodeStatus;
import com.kwikquant.strategy.domain.StrategyDefinition;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/** 集成测试：StrategyCodeMapper SQL + StrategyCode 全字段往返。 */
@SpringBootTest(classes = KwikquantApplication.class)
@TestPropertySource(
        properties = {
            "JWT_SECRET=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
            "ENCRYPTION_KEY=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
        })
class StrategyCodeMapperIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    StrategyMapper strategyMapper;

    @Autowired
    StrategyCodeMapper codeMapper;

    /** Return seed (strategyId, userId) pair for tests that need userId for deep-defense mapper calls. */
    private record Seed(long strategyId, long userId) {}

    private Seed seedStrategy() {
        long userId = System.nanoTime() % 1_000_000L + 1_000_000L;
        StrategyDefinition s = StrategyDefinition.create(userId, "n", null, "BTC/USDT", "BINANCE", "SPOT", "1h", "{}");
        strategyMapper.insert(s);
        return new Seed(s.getId(), userId);
    }

    @Test
    void insertAndFindById_roundTripsAllFields() {
        long strategyId = seedStrategy().strategyId();
        StrategyCode code = StrategyCode.create(strategyId, 1, "def on_bar(): pass", "v1");
        codeMapper.insert(code);
        assertThat(code.getId()).isNotNull();

        StrategyCode loaded = codeMapper.findById(code.getId());
        assertThat(loaded).isNotNull();
        assertThat(loaded.getStrategyId()).isEqualTo(strategyId);
        assertThat(loaded.getVersionNumber()).isEqualTo(1);
        assertThat(loaded.getSourceCode()).isEqualTo("def on_bar(): pass");
        assertThat(loaded.getStatus()).isEqualTo(StrategyCodeStatus.DRAFT);
        assertThat(loaded.getLanguage()).isEqualTo("python");
        assertThat(loaded.getChangelog()).isEqualTo("v1");
        assertThat(loaded.getCreatedAt()).isNotNull();
    }

    @Test
    void findMaxVersionNumber_increments() {
        long strategyId = seedStrategy().strategyId();
        assertThat(codeMapper.findMaxVersionNumber(strategyId)).isZero();

        codeMapper.insert(StrategyCode.create(strategyId, 1, "a", null));
        assertThat(codeMapper.findMaxVersionNumber(strategyId)).isEqualTo(1);
        codeMapper.insert(StrategyCode.create(strategyId, 3, "b", null));
        assertThat(codeMapper.findMaxVersionNumber(strategyId)).isEqualTo(3);
    }

    @Test
    void publishAndArchive_atomicSemantics() {
        Seed seed = seedStrategy();
        long strategyId = seed.strategyId();
        long userId = seed.userId();
        StrategyCode v1 = StrategyCode.create(strategyId, 1, "v1", null);
        codeMapper.insert(v1);
        codeMapper.updateStatus(v1.getId(), userId, "DRAFT", "PUBLISHED");
        StrategyCode v2 = StrategyCode.create(strategyId, 2, "v2", null);
        codeMapper.insert(v2);

        // 归档旧 PUBLISHED + 发布 v2
        assertThat(codeMapper.archiveCurrentPublished(strategyId, userId)).isEqualTo(1);
        assertThat(codeMapper.updateStatus(v2.getId(), userId, "DRAFT", "PUBLISHED"))
                .isEqualTo(1);

        assertThat(codeMapper.findPublishedByStrategyId(strategyId).getId()).isEqualTo(v2.getId());
        assertThat(codeMapper.findById(v1.getId()).getStatus()).isEqualTo(StrategyCodeStatus.ARCHIVED);
    }

    @Test
    void updateDraft_onlyOnDraftStatus() {
        Seed seed = seedStrategy();
        long strategyId = seed.strategyId();
        long userId = seed.userId();
        StrategyCode code = StrategyCode.create(strategyId, 1, "orig", null);
        codeMapper.insert(code);
        assertThat(codeMapper.updateDraft(code.getId(), userId, "edited", "c1")).isEqualTo(1);
        // PUBLISHED 后 updateDraft 不生效
        codeMapper.updateStatus(code.getId(), userId, "DRAFT", "PUBLISHED");
        assertThat(codeMapper.updateDraft(code.getId(), userId, "nope", null)).isZero();
    }

    @Test
    void deepDefense_wrongUserIdBlocksAllUpdates() {
        Seed seed = seedStrategy();
        long strategyId = seed.strategyId();
        long userId = seed.userId();
        StrategyCode code = StrategyCode.create(strategyId, 1, "orig", null);
        codeMapper.insert(code);

        long badUser = userId + 999;
        assertThat(codeMapper.updateDraft(code.getId(), badUser, "hijack", null))
                .isZero();
        assertThat(codeMapper.updateStatus(code.getId(), badUser, "DRAFT", "PUBLISHED"))
                .isZero();
        assertThat(codeMapper.archiveCurrentPublished(strategyId, badUser)).isZero();
    }
}
