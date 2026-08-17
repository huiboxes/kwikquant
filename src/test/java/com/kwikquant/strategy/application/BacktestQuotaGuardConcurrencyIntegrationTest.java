package com.kwikquant.strategy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.kwikquant.AbstractIntegrationTest;
import com.kwikquant.KwikquantApplication;
import com.kwikquant.account.domain.User;
import com.kwikquant.account.infrastructure.UserMapper;
import com.kwikquant.strategy.domain.BacktestQuotaExceededException;
import com.kwikquant.strategy.domain.BacktestTask;
import com.kwikquant.strategy.domain.StrategyCode;
import com.kwikquant.strategy.domain.StrategyDefinition;
import com.kwikquant.strategy.infrastructure.BacktestTaskMapper;
import com.kwikquant.strategy.infrastructure.StrategyCodeMapper;
import com.kwikquant.strategy.infrastructure.StrategyMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * 配额并发集成测试(真实 PostgreSQL):同一用户并发提交、默认配额 2 时恰好 2 个插入成功。
 *
 * <p>复现修复前的 write skew:无 {@code pg_advisory_xact_lock} 时三线程同时 count=0 → 三插入,
 * 本测试断言 {@code inserted == 2}(advisory lock 串行化后结果确定,非概率性通过)。
 *
 * <p>不加 {@code @TestPropertySource}(与标准集成 context 共享缓存):自造属性集会分裂出第二个
 * Spring context,第二个 context 的 lease acquire 会与首个冲突(单节点不变量)。3 线程 × 配额 2
 * 达到与"配额 1 × 2 线程"同等的竞态强度。
 */
@SpringBootTest(classes = KwikquantApplication.class)
@TestPropertySource(
        properties = {
            "JWT_SECRET=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
            "ENCRYPTION_KEY=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
        })
class BacktestQuotaGuardConcurrencyIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    BacktestQuotaGuard quotaGuard;

    @Autowired
    BacktestTaskMapper taskMapper;

    @Autowired
    StrategyMapper strategyMapper;

    @Autowired
    StrategyCodeMapper codeMapper;

    @Autowired
    UserMapper userMapper;

    private long[] seed() {
        String uname = "bt-quota-" + System.nanoTime();
        User user = new User();
        user.setUsername(uname);
        user.setEmail(uname + "@example.com");
        user.setPasswordHash("$argon2id$stub");
        user.setEnabled(true);
        userMapper.insert(user);
        StrategyDefinition s =
                StrategyDefinition.create(user.getId(), "n", null, "BTC/USDT", "BINANCE", "SPOT", "1h", "{}");
        strategyMapper.insert(s);
        StrategyCode code = StrategyCode.create(s.getId(), 1, "def on_bar(): pass", null);
        codeMapper.insert(code);
        return new long[] {s.getId(), code.getId(), user.getId()};
    }

    @Test
    void concurrentInserts_sameUser_quotaTwo_exactlyTwoSucceed() throws Exception {
        long[] ids = seed();
        long strategyId = ids[0], codeId = ids[1], userId = ids[2];
        Instant start = Instant.parse("2025-01-01T00:00:00Z");
        Instant end = Instant.parse("2025-02-01T00:00:00Z");
        List<BacktestTask> candidates = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            candidates.add(BacktestTask.create(
                    strategyId, userId, codeId, "BTC/USDT", "BINANCE", "SPOT", "1h", start, end, "{}"));
        }

        ExecutorService pool = Executors.newFixedThreadPool(3);
        CountDownLatch ready = new CountDownLatch(3);
        CountDownLatch go = new CountDownLatch(1);
        List<Long> insertedIds = Collections.synchronizedList(new ArrayList<>());
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        for (BacktestTask t : candidates) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    quotaGuard.insertWithinQuota(t);
                    insertedIds.add(t.getId());
                } catch (Throwable e) {
                    failures.add(e);
                }
            });
        }
        ready.await(10, TimeUnit.SECONDS);
        go.countDown(); // 三线程同时起跑,最大化 count-then-insert 重叠窗口
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        // 竞态修复语义:3 并发 × 配额 2 → 恰好 2 成功 1 拒绝(无锁时三者都读到 count=0 → 3 插入)
        assertThat(insertedIds).hasSize(2);
        assertThat(failures).hasSize(1);
        BacktestQuotaExceededException ex = (BacktestQuotaExceededException) failures.get(0);
        assertThat(ex.active()).isEqualTo(2);
        assertThat(ex.max()).isEqualTo(2);
        assertThat(taskMapper.countActiveByUser(userId)).isEqualTo(2);
    }

    @Test
    void sequentialInserts_quotaTwo_thirdRejected() {
        long[] ids = seed();
        long strategyId = ids[0], codeId = ids[1], userId = ids[2];
        Instant start = Instant.parse("2025-01-01T00:00:00Z");
        Instant end = Instant.parse("2025-02-01T00:00:00Z");

        quotaGuard.insertWithinQuota(
                BacktestTask.create(strategyId, userId, codeId, "BTC/USDT", "BINANCE", "SPOT", "1h", start, end, "{}"));
        quotaGuard.insertWithinQuota(
                BacktestTask.create(strategyId, userId, codeId, "BTC/USDT", "BINANCE", "SPOT", "1h", start, end, "{}"));

        BacktestQuotaExceededException ex = assertThrows(
                BacktestQuotaExceededException.class,
                () -> quotaGuard.insertWithinQuota(BacktestTask.create(
                        strategyId, userId, codeId, "BTC/USDT", "BINANCE", "SPOT", "1h", start, end, "{}")));
        assertThat(ex.active()).isEqualTo(2);
        assertThat(ex.max()).isEqualTo(2);
        assertThat(taskMapper.countActiveByUser(userId)).isEqualTo(2);
    }
}
