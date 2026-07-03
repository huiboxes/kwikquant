package com.kwikquant.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.kwikquant.AbstractIntegrationTest;
import com.kwikquant.account.domain.User;
import com.kwikquant.account.infrastructure.UserMapper;
import com.kwikquant.report.application.ReportService;
import com.kwikquant.report.domain.BacktestReport;
import com.kwikquant.shared.infra.WorkerTokenService;
import com.kwikquant.shared.types.StrategyStatus;
import com.kwikquant.strategy.application.BacktestResult;
import com.kwikquant.strategy.application.BacktestRunner;
import com.kwikquant.strategy.application.BacktestTaskService;
import com.kwikquant.strategy.domain.BacktestTask;
import com.kwikquant.strategy.domain.BacktestTaskStatus;
import com.kwikquant.strategy.domain.StrategyCode;
import com.kwikquant.strategy.domain.StrategyCodeStatus;
import com.kwikquant.strategy.domain.StrategyDefinition;
import com.kwikquant.strategy.infrastructure.BacktestTaskMapper;
import com.kwikquant.strategy.infrastructure.StrategyCodeMapper;
import com.kwikquant.strategy.infrastructure.StrategyMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * §7.1 六链路 - 回测 E2E:BacktestTaskService.submit → executeAsync → WTS.issueToken →
 * initLedger(SPI) → mock BacktestRunner 返回 §8 → ReportService.submitBacktestResult →
 * backtest_reports + trade_records 落库 → task COMPLETED,finally cleanupLedger + revokeToken。
 *
 * <p>SyncAsync 让 {@code @Async executeAsync} 在测试线程内跑,断言无需 Awaitility 轮询(与
 * RiskNotificationE2ETest 相同套路);SimpMessagingTemplate + BacktestRunner mock,其余组件真实。
 */
@Import(BacktestE2ETest.SyncAsyncConfig.class)
class BacktestE2ETest extends AbstractIntegrationTest {

    @Autowired
    UserMapper userMapper;

    @Autowired
    StrategyMapper strategyMapper;

    @Autowired
    StrategyCodeMapper codeMapper;

    @Autowired
    BacktestTaskMapper taskMapper;

    @Autowired
    BacktestTaskService backtestTaskService;

    @Autowired
    ReportService reportService;

    @Autowired
    WorkerTokenService workerTokenService;

    @MockitoBean
    SimpMessagingTemplate simpMessagingTemplate;

    @MockitoBean
    BacktestRunner backtestRunner;

    @TestConfiguration
    static class SyncAsyncConfig implements AsyncConfigurer {
        @Override
        public java.util.concurrent.Executor getAsyncExecutor() {
            return Runnable::run; // synchronous
        }
    }

    @Test
    void backtest_endToEnd_producesReportAndCompletesTask() {
        // --- prepare user + strategy + code ---
        User u = new User();
        u.setUsername("bt-user-" + System.nanoTime());
        u.setEmail(u.getUsername() + "@e2e.test");
        u.setPasswordHash("h");
        userMapper.insert(u);

        StrategyDefinition strat =
                StrategyDefinition.create(u.getId(), "MA-Strategy", null, "BTC/USDT", "BINANCE", "SPOT", "1h", "{}");
        strat.setStatus(StrategyStatus.READY);
        strategyMapper.insert(strat);

        StrategyCode code = StrategyCode.create(strat.getId(), 1, "def on_bar(bar): pass", "v1");
        code.setStatus(StrategyCodeStatus.PUBLISHED);
        codeMapper.insert(code);

        // --- stub the Python runner to return a valid §8 JSON ---
        String section8 =
                """
            {
              "name":"MA-Strategy",
              "params":{},
              "symbol":"BTC/USDT",
              "timeframe":"1h",
              "period":{"start":"2024-01-01T00:00:00Z","end":"2024-01-02T00:00:00Z"},
              "trades":[
                {"time":"2024-01-01T08:00:00Z","side":"buy","price":"42150","amount":"0.1","fee":"0.4215"}
              ],
              "equity_curve":[
                {"time":"2024-01-01T00:00:00Z","equity":"100000"},
                {"time":"2024-01-02T00:00:00Z","equity":"100050"}
              ]
            }
            """;
        when(backtestRunner.run(any())).thenReturn(new BacktestResult(new BigDecimal("50"), 1, section8));

        // --- submit backtest ---
        BacktestTask submitted = backtestTaskService.submit(
                strat.getId(),
                u.getId(),
                "BTC/USDT",
                "BINANCE",
                "1h",
                Instant.parse("2024-01-01T00:00:00Z"),
                Instant.parse("2024-01-02T00:00:00Z"),
                "{\"initial_capital\":\"100000\"}");

        // 由于 executor=Runnable::run,executeAsync 已在 submit 内同步执行完毕。
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            BacktestTask completed = taskMapper.findById(submitted.getId());
            assertThat(completed).isNotNull();
            assertThat(completed.getStatus()).isEqualTo(BacktestTaskStatus.COMPLETED);
            assertThat(completed.getResult()).isNotBlank();
            assertThat(completed.getResult()).contains("realizedPnl");
        });

        // backtest_reports 应该有 1 条记录(source=PLATFORM)
        var reports = reportService.listByUser(u.getId(), null, 1, 10);
        assertThat(reports.content()).isNotEmpty();
        BacktestReport rpt = reports.content().get(0);
        assertThat(rpt.getSource()).isEqualTo("PLATFORM");
        assertThat(rpt.getSymbol()).isEqualTo("BTC/USDT");
        // trades 已通过 tradeRecordMapper.batchInsert 入库,可直接查回验证
        assertThat(reportService.getTradeRecords(rpt.getId(), u.getId())).hasSize(1);

        // token 被 revoke(BEG finally),ledger cleanup(内存,无从直接观察但流程无异常即可)
        // WS 推 COMPLETED 事件
        org.mockito.Mockito.verify(simpMessagingTemplate)
                .convertAndSend(
                        org.mockito.ArgumentMatchers.eq("/topic/backtests/" + u.getId()),
                        org.mockito.ArgumentMatchers.argThat((Object o) ->
                                o instanceof java.util.Map<?, ?> m && "COMPLETED".equals(m.get("status"))));
    }
}
