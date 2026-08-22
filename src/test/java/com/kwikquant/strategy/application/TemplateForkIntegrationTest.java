package com.kwikquant.strategy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.kwikquant.AbstractIntegrationTest;
import com.kwikquant.account.domain.User;
import com.kwikquant.account.infrastructure.UserMapper;
import com.kwikquant.shared.types.StrategyStatus;
import com.kwikquant.strategy.domain.BacktestTask;
import com.kwikquant.strategy.domain.BacktestTaskStatus;
import com.kwikquant.strategy.domain.StrategyCode;
import com.kwikquant.strategy.domain.StrategyCodeStatus;
import com.kwikquant.strategy.domain.StrategyDefinition;
import com.kwikquant.strategy.domain.StrategyTemplate;
import com.kwikquant.strategy.domain.TemplateNotFoundException;
import com.kwikquant.strategy.infrastructure.BacktestTaskMapper;
import com.kwikquant.strategy.infrastructure.StrategyCodeMapper;
import com.kwikquant.strategy.infrastructure.StrategyMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 模板 fork 全链路集成测试：fork 落库（策略 + 已发布代码 + 就绪）→ 自动首回测提交 → 异步执行完成。
 *
 * <p>{@code BacktestRunner} mock 掉（不真起 python），{@code BacktestWorkerHealthChecker} mock 可用，
 * 与 {@code BacktestE2ETest} 同手法；backtestExecutor 是独立线程池，终态断言走 Awaitility。
 */
class TemplateForkIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    UserMapper userMapper;

    @Autowired
    StrategyMapper strategyMapper;

    @Autowired
    StrategyCodeMapper codeMapper;

    @Autowired
    BacktestTaskMapper taskMapper;

    @Autowired
    StrategyTemplateService templateService;

    @Autowired
    StrategyTemplateCatalog catalog;

    @MockitoBean
    SimpMessagingTemplate simpMessagingTemplate;

    @MockitoBean
    BacktestRunner backtestRunner;

    @MockitoBean
    BacktestWorkerHealthChecker workerHealthChecker;

    @Test
    void fork_createsStrategyWithPublishedCode_andRunsFirstBacktest() {
        when(workerHealthChecker.isAvailable()).thenReturn(true);
        when(backtestRunner.run(any())).thenAnswer(inv -> new BacktestResult(new BigDecimal("12.5"), 2, section8()));
        User user = newUser();

        TemplateForkResult result = templateService.fork("ma-double-cross", user.getId());

        // 1. 策略落库：模板默认值 + READY（源码已发布，可直接启动）
        StrategyDefinition strategy = result.strategy();
        assertThat(strategy.getId()).isNotNull();
        StrategyTemplate template = catalog.get("ma-double-cross");
        assertThat(strategy.getName()).isEqualTo(template.name());
        assertThat(strategy.getSymbol()).isEqualTo(template.symbol());
        assertThat(strategy.getExchange()).isEqualTo(template.exchange());
        assertThat(strategy.getIntervalValue()).isEqualTo(template.intervalValue());
        assertThat(strategy.getMarketType()).isEqualTo("SPOT");
        assertThat(strategy.getStatus()).isEqualTo(StrategyStatus.READY);

        // 2. 代码已发布（fork 产物出生即可回测/启动）
        StrategyCode published = codeMapper.findPublishedByStrategyId(strategy.getId());
        assertThat(published).isNotNull();
        assertThat(published.getStatus()).isEqualTo(StrategyCodeStatus.PUBLISHED);
        assertThat(published.getSourceCode()).isEqualTo(template.sourceCode());
        assertThat(published.getChangelog()).contains("ma-double-cross");

        // 3. 首回测已提交（模板推荐窗口），异步执行到终态
        assertThat(result.firstBacktestTaskId()).isNotNull();
        assertThat(result.backtestSkipReason()).isNull();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            BacktestTask task = taskMapper.findById(result.firstBacktestTaskId());
            assertThat(task).isNotNull();
            assertThat(task.getStrategyId()).isEqualTo(strategy.getId());
            assertThat(task.getSymbol()).isEqualTo(template.symbol());
            assertThat(task.getIntervalValue()).isEqualTo(template.intervalValue());
            assertThat(Duration.between(task.getStartTime(), task.getEndTime()))
                    .isEqualTo(Duration.ofDays(template.backtestWindowDays()));
            assertThat(task.getStatus()).isEqualTo(BacktestTaskStatus.COMPLETED);
        });
    }

    @Test
    void fork_secondTime_createsAnotherStrategy() {
        when(workerHealthChecker.isAvailable()).thenReturn(true);
        when(backtestRunner.run(any())).thenAnswer(inv -> new BacktestResult(BigDecimal.ZERO, 0, section8()));
        User user = newUser();

        TemplateForkResult first = templateService.fork("ma-double-cross", user.getId());
        TemplateForkResult second = templateService.fork("ma-double-cross", user.getId());

        assertThat(second.strategy().getId()).isNotEqualTo(first.strategy().getId());
        List<StrategyDefinition> all = strategyMapper.findByUserId(user.getId());
        assertThat(all).hasSize(2);
        // 等两个异步任务跑完再结束方法:防测试间 MockitoBean reset 清掉 runner stub 后异步线程才调度(NPE 日志噪音)
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            List<BacktestTask> tasks = taskMapper.findByUserId(user.getId());
            assertThat(tasks).hasSize(2);
            assertThat(tasks).allSatisfy(t -> assertThat(t.getStatus()).isEqualTo(BacktestTaskStatus.COMPLETED));
        });
    }

    @Test
    void fork_workerUnavailable_forkSucceedsAndSkipsBacktest() {
        when(workerHealthChecker.isAvailable()).thenReturn(false);
        when(workerHealthChecker.detail()).thenReturn("自检失败(mock)");
        User user = newUser();

        TemplateForkResult result = templateService.fork("ma-double-cross", user.getId());

        // fork 不受回测环境影响：策略 + 已发布代码落库，回测降级为 skipReason
        assertThat(result.strategy().getId()).isNotNull();
        assertThat(codeMapper.findPublishedByStrategyId(result.strategy().getId()))
                .isNotNull();
        assertThat(result.firstBacktestTaskId()).isNull();
        assertThat(result.backtestSkipReason()).contains("不可用");
    }

    @Test
    void fork_unknownKey_throwsTemplateNotFound() {
        User user = newUser();
        assertThatThrownBy(() -> templateService.fork("no-such-template", user.getId()))
                .isInstanceOf(TemplateNotFoundException.class);
        assertThat(strategyMapper.findByUserId(user.getId())).isEmpty();
    }

    private User newUser() {
        User u = new User();
        u.setUsername("tpl-user-" + System.nanoTime());
        u.setEmail(u.getUsername() + "@tpl.test");
        u.setPasswordHash("h");
        userMapper.insert(u);
        return u;
    }

    /** 最小合法 section8 回测结果（ReportService.submitBacktestResult 可解析）。 */
    private static String section8() {
        return """
        {
          "name":"模板策略",
          "params":{},
          "symbol":"BTC/USDT",
          "timeframe":"1h",
          "period":{"start":"2024-01-01T00:00:00Z","end":"2024-01-02T00:00:00Z"},
          "trades":[
            {"time":"2024-01-01T08:00:00Z","side":"buy","price":"42150","amount":"0.1","fee":"0.4215"}
          ],
          "equity_curve":[
            {"time":"2024-01-01T00:00:00Z","equity":"100000"},
            {"time":"2024-01-02T00:00:00Z","equity":"100012.5"}
          ]
        }
        """;
    }
}
