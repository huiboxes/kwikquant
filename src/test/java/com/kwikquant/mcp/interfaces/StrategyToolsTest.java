package com.kwikquant.mcp.interfaces;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kwikquant.account.application.ExchangeAccountService;
import com.kwikquant.account.domain.ExchangeAccount;
import com.kwikquant.mcp.interfaces.view.BacktestReportPageView;
import com.kwikquant.mcp.interfaces.view.BacktestResultView;
import com.kwikquant.mcp.interfaces.view.ComparisonView;
import com.kwikquant.mcp.interfaces.view.StrategyView;
import com.kwikquant.report.application.ComparisonResult;
import com.kwikquant.report.application.ReportComparisonService;
import com.kwikquant.report.application.ReportService;
import com.kwikquant.report.domain.BacktestReport;
import com.kwikquant.shared.infra.McpEmergencyConfirmRequiredException;
import com.kwikquant.shared.infra.McpToolParamInvalidException;
import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.PageDto;
import com.kwikquant.shared.types.StrategyStatus;
import com.kwikquant.strategy.application.BacktestTaskService;
import com.kwikquant.strategy.application.StrategyCrudService;
import com.kwikquant.strategy.application.StrategyLifecycleService;
import com.kwikquant.strategy.domain.BacktestTask;
import com.kwikquant.strategy.domain.BacktestTaskStatus;
import com.kwikquant.strategy.domain.StrategyDefinition;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link StrategyTools} 单测。验证：run_backtest 双模式（提交+轮询/查询）+ 60s 超时降级 + FAILED、
 * list_backtests 分页、compare_backtests 对比、start_paper/live 的 exchange 匹配 + paperTrading 匹配 + confirm。
 * 轮询间隔注入 0 避免阻塞。
 */
class StrategyToolsTest {

    private BacktestTaskService backtestTaskService;
    private ReportService reportService;
    private ReportComparisonService comparisonService;
    private StrategyCrudService strategyCrudService;
    private StrategyLifecycleService lifecycleService;
    private ExchangeAccountService accountService;
    private StrategyTools tools;

    @BeforeEach
    void setUp() {
        backtestTaskService = mock(BacktestTaskService.class);
        reportService = mock(ReportService.class);
        comparisonService = mock(ReportComparisonService.class);
        strategyCrudService = mock(StrategyCrudService.class);
        lifecycleService = mock(StrategyLifecycleService.class);
        accountService = mock(ExchangeAccountService.class);
        // pollIntervalMs=0 + pollMaxAttempts=6 → 轮询不阻塞
        tools = new StrategyTools(
                backtestTaskService,
                reportService,
                comparisonService,
                strategyCrudService,
                lifecycleService,
                accountService,
                new ObjectMapper(),
                0L,
                6);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("42", "x"));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ── run_backtest 提交模式 ──

    @Test
    void runBacktest_submitMode_completedOnSecondPoll_returnsResult() {
        BacktestTask submitted = task(42L, BacktestTaskStatus.PENDING, null, null);
        when(backtestTaskService.submit(
                        eq(1L),
                        eq(42L),
                        eq("BTC/USDT"),
                        eq(null),
                        eq("1h"),
                        any(Instant.class),
                        any(Instant.class),
                        any(String.class)))
                .thenReturn(submitted);
        BacktestTask running = task(42L, BacktestTaskStatus.RUNNING, null, null);
        BacktestTask completed = task(42L, BacktestTaskStatus.COMPLETED, "{\"metrics\":{}}", null);
        when(backtestTaskService.getOwned(42L, 42L)).thenReturn(running, completed);

        BacktestResultView v =
                tools.runBacktest(1L, null, "BTC/USDT", "1h", "2024-01-01T00:00:00Z", "2024-02-01T00:00:00Z", Map.of());

        assertThat(v.taskId()).isEqualTo(42L);
        assertThat(v.status()).isEqualTo("COMPLETED");
        assertThat(v.result()).isEqualTo("{\"metrics\":{}}");
        ArgumentCaptor<String> paramsCaptor = ArgumentCaptor.forClass(String.class);
        verify(backtestTaskService)
                .submit(
                        eq(1L),
                        eq(42L),
                        eq("BTC/USDT"),
                        eq(null),
                        eq("1h"),
                        any(Instant.class),
                        any(Instant.class),
                        paramsCaptor.capture());
        assertThat(paramsCaptor.getValue()).isEqualTo("{}");
    }

    @Test
    void runBacktest_submitMode_timeout_returns200RunningHint() {
        BacktestTask submitted = task(42L, BacktestTaskStatus.PENDING, null, null);
        when(backtestTaskService.submit(anyLong(), anyLong(), any(), any(), any(), any(), any(), any()))
                .thenReturn(submitted);
        BacktestTask running = task(42L, BacktestTaskStatus.RUNNING, null, null);
        // 6 次都 RUNNING
        when(backtestTaskService.getOwned(42L, 42L)).thenReturn(running, running, running, running, running, running);

        BacktestResultView v =
                tools.runBacktest(1L, null, "BTC/USDT", "1h", "2024-01-01T00:00:00Z", "2024-02-01T00:00:00Z", null);

        assertThat(v.status()).isEqualTo("RUNNING");
        assertThat(v.hint()).contains("taskId=42");
    }

    @Test
    void runBacktest_submitMode_failed_returnsFailedWithErrorMessage() {
        BacktestTask submitted = task(42L, BacktestTaskStatus.PENDING, null, null);
        when(backtestTaskService.submit(anyLong(), anyLong(), any(), any(), any(), any(), any(), any()))
                .thenReturn(submitted);
        BacktestTask failed = task(42L, BacktestTaskStatus.FAILED, null, "worker crashed");
        when(backtestTaskService.getOwned(42L, 42L)).thenReturn(failed);

        BacktestResultView v =
                tools.runBacktest(1L, null, "BTC/USDT", "1h", "2024-01-01T00:00:00Z", "2024-02-01T00:00:00Z", null);

        assertThat(v.status()).isEqualTo("FAILED");
        assertThat(v.errorMessage()).isEqualTo("worker crashed");
    }

    @Test
    void runBacktest_queryMode_completed_returnsResult() {
        BacktestTask completed = task(42L, BacktestTaskStatus.COMPLETED, "{\"x\":1}", null);
        when(backtestTaskService.getOwned(42L, 42L)).thenReturn(completed);

        BacktestResultView v = tools.runBacktest(null, 42L, null, null, null, null, null);

        assertThat(v.status()).isEqualTo("COMPLETED");
        assertThat(v.result()).isEqualTo("{\"x\":1}");
    }

    /** R4-04: 查询模式续查仍 RUNNING 返无 hint 视图（与提交超时的有 hint 形成对照）。 */
    @Test
    void runBacktest_queryMode_running_returnsRunningNoHint() {
        BacktestTask running = task(42L, BacktestTaskStatus.RUNNING, null, null);
        when(backtestTaskService.getOwned(42L, 42L)).thenReturn(running);

        BacktestResultView v = tools.runBacktest(null, 42L, null, null, null, null, null);

        assertThat(v.status()).isEqualTo("RUNNING");
        assertThat(v.hint()).isNull();
        assertThat(v.result()).isNull();
    }

    @Test
    void runBacktest_queryMode_failed_returnsFailedWithErrorMessage() {
        BacktestTask failed = task(42L, BacktestTaskStatus.FAILED, null, "worker crashed");
        when(backtestTaskService.getOwned(42L, 42L)).thenReturn(failed);

        BacktestResultView v = tools.runBacktest(null, 42L, null, null, null, null, null);

        assertThat(v.status()).isEqualTo("FAILED");
        assertThat(v.errorMessage()).isEqualTo("worker crashed");
    }

    /** R4-02: 非空 params 走 objectMapper.writeValueAsString 序列化（非 "{}" 短路）。 */
    @Test
    void runBacktest_nonEmptyParams_serializedToJsonString() {
        BacktestTask submitted = task(42L, BacktestTaskStatus.PENDING, null, null);
        when(backtestTaskService.submit(anyLong(), anyLong(), any(), any(), any(), any(), any(), any()))
                .thenReturn(submitted);
        BacktestTask completed = task(42L, BacktestTaskStatus.COMPLETED, "{\"ok\":1}", null);
        when(backtestTaskService.getOwned(42L, 42L)).thenReturn(completed);

        tools.runBacktest(
                1L,
                null,
                "BTC/USDT",
                "1h",
                "2024-01-01T00:00:00Z",
                "2024-02-01T00:00:00Z",
                Map.of("fast", false, "threshold", 100));

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(backtestTaskService).submit(anyLong(), anyLong(), any(), any(), any(), any(), any(), captor.capture());
        String paramsJson = captor.getValue();
        assertThat(paramsJson).isNotEqualTo("{}");
        assertThat(paramsJson).contains("fast", "threshold");
    }

    /** R4-02: params 含不可序列化对象（循环引用）→ JacksonException → McpToolParamInvalidException(10002)。 */
    @Test
    void runBacktest_unserializableParams_throws10002() {
        java.util.HashMap<String, Object> cyclic = new java.util.HashMap<>();
        cyclic.put("self", cyclic);

        assertThatThrownBy(() -> tools.runBacktest(
                        1L, null, "BTC/USDT", "1h", "2024-01-01T00:00:00Z", "2024-02-01T00:00:00Z", cyclic))
                .isInstanceOf(com.kwikquant.shared.infra.McpToolParamInvalidException.class)
                .hasMessageContaining("params");
    }

    @Test
    void runBacktest_neitherId_throws10002() {
        assertThatThrownBy(() -> tools.runBacktest(
                        null, null, "BTC/USDT", "1h", "2024-01-01T00:00:00Z", "2024-02-01T00:00:00Z", null))
                .isInstanceOf(McpToolParamInvalidException.class)
                .hasMessageContaining("strategyId");
    }

    @Test
    void runBacktest_invalidStart_throws10002() {
        assertThatThrownBy(
                        () -> tools.runBacktest(1L, null, "BTC/USDT", "1h", "not-a-date", "2024-02-01T00:00:00Z", null))
                .isInstanceOf(McpToolParamInvalidException.class)
                .hasMessageContaining("start");
    }

    // ── list_backtests ──

    @Test
    void listBacktests_returnsPageView() {
        BacktestReport r = new BacktestReport();
        r.setId(7L);
        r.setSymbol("BTC/USDT");
        r.setTimeframe("1h");
        r.setTotalReturn(new BigDecimal("0.15"));
        r.setSharpeRatio(new BigDecimal("1.8"));
        r.setMaxDrawdown(new BigDecimal("-0.05"));
        r.setWinRate(new BigDecimal("0.55"));
        r.setProfitFactor(new BigDecimal("1.4"));
        r.setTotalTrades(42);
        PageDto<BacktestReport> page = new PageDto<>(List.of(r), 1, 20, 1L, 1);
        when(reportService.listByUser(42L, "BTC/USDT", 1, 20)).thenReturn(page);

        BacktestReportPageView v = tools.listBacktests("BTC/USDT", 1, 20);

        assertThat(v.items()).hasSize(1);
        assertThat(v.items().get(0).totalReturn()).isEqualByComparingTo("0.15");
        assertThat(v.total()).isEqualTo(1L);
    }

    @Test
    void listBacktests_nullParams_defaultsApplied() {
        PageDto<BacktestReport> page = new PageDto<>(List.of(), 1, 20, 0L, 0);
        when(reportService.listByUser(42L, null, 1, 20)).thenReturn(page);

        BacktestReportPageView v = tools.listBacktests(null, null, null);

        assertThat(v.items()).isEmpty();
        verify(reportService).listByUser(42L, null, 1, 20);
    }

    // ── compare_backtests ──

    @Test
    void compareBacktests_returnsComparisonView() {
        BacktestReport r = new BacktestReport();
        r.setId(7L);
        r.setTotalReturn(new BigDecimal("0.15"));
        ComparisonResult result = new ComparisonResult(List.of(r), Map.of("totalReturn", List.of(7L)));
        when(comparisonService.compare(List.of(7L), 42L)).thenReturn(result);

        ComparisonView v = tools.compareBacktests(List.of(7L));

        assertThat(v.reports()).hasSize(1);
        assertThat(v.ranking()).containsEntry("totalReturn", List.of(7L));
    }

    // ── start_paper_trading ──

    @Test
    void startPaperTrading_valid_startsAndReturnsStrategyView() {
        StrategyDefinition strategy = strategy(1L, "BINANCE", StrategyStatus.READY);
        when(strategyCrudService.getOwned(1L, 42L)).thenReturn(strategy);
        when(accountService.getOwned(1L, 42L)).thenReturn(account(1L, Exchange.BINANCE, true));
        StrategyDefinition running = strategy(1L, "BINANCE", StrategyStatus.RUNNING);
        when(lifecycleService.start(1L, 42L)).thenReturn(running);

        StrategyView v = tools.startPaperTrading(1L, 1L);

        assertThat(v.status()).isEqualTo(StrategyStatus.RUNNING);
        verify(lifecycleService).start(1L, 42L);
    }

    @Test
    void startPaperTrading_paperTradingFalse_throws10002() {
        when(strategyCrudService.getOwned(1L, 42L)).thenReturn(strategy(1L, "BINANCE", StrategyStatus.READY));
        when(accountService.getOwned(1L, 42L)).thenReturn(account(1L, Exchange.BINANCE, false));

        assertThatThrownBy(() -> tools.startPaperTrading(1L, 1L))
                .isInstanceOf(McpToolParamInvalidException.class)
                .hasMessageContaining("paperTrading=true");
    }

    @Test
    void startPaperTrading_exchangeMismatch_throws10002() {
        when(strategyCrudService.getOwned(1L, 42L)).thenReturn(strategy(1L, "BINANCE", StrategyStatus.READY));
        when(accountService.getOwned(1L, 42L)).thenReturn(account(1L, Exchange.OKX, true));

        assertThatThrownBy(() -> tools.startPaperTrading(1L, 1L))
                .isInstanceOf(McpToolParamInvalidException.class)
                .hasMessageContaining("exchange");
    }

    // ── start_live_trading ──

    @Test
    void startLiveTrading_missingConfirm_throws10004() {
        assertThatThrownBy(() -> tools.startLiveTrading(1L, 1L, null))
                .isInstanceOf(McpEmergencyConfirmRequiredException.class)
                .hasMessageContaining("confirm");
    }

    @Test
    void startLiveTrading_confirmFalse_throws10004() {
        assertThatThrownBy(() -> tools.startLiveTrading(1L, 1L, false))
                .isInstanceOf(McpEmergencyConfirmRequiredException.class);
    }

    @Test
    void startLiveTrading_valid_startsAndReturnsStrategyView() {
        when(strategyCrudService.getOwned(1L, 42L)).thenReturn(strategy(1L, "BINANCE", StrategyStatus.READY));
        when(accountService.getOwned(1L, 42L)).thenReturn(account(1L, Exchange.BINANCE, false));
        when(lifecycleService.start(1L, 42L)).thenReturn(strategy(1L, "BINANCE", StrategyStatus.RUNNING));

        StrategyView v = tools.startLiveTrading(1L, 1L, true);

        assertThat(v.status()).isEqualTo(StrategyStatus.RUNNING);
        verify(lifecycleService).start(1L, 42L);
    }

    @Test
    void startLiveTrading_paperTradingTrue_throws10002() {
        when(strategyCrudService.getOwned(1L, 42L)).thenReturn(strategy(1L, "BINANCE", StrategyStatus.READY));
        when(accountService.getOwned(1L, 42L)).thenReturn(account(1L, Exchange.BINANCE, true));

        assertThatThrownBy(() -> tools.startLiveTrading(1L, 1L, true))
                .isInstanceOf(McpToolParamInvalidException.class)
                .hasMessageContaining("paperTrading=false");
    }

    @Test
    void startLiveTrading_exchangeMismatch_throws10002() {
        when(strategyCrudService.getOwned(1L, 42L)).thenReturn(strategy(1L, "BINANCE", StrategyStatus.READY));
        when(accountService.getOwned(1L, 42L)).thenReturn(account(1L, Exchange.OKX, false));

        assertThatThrownBy(() -> tools.startLiveTrading(1L, 1L, true))
                .isInstanceOf(McpToolParamInvalidException.class)
                .hasMessageContaining("exchange");
    }

    private static BacktestTask task(long id, BacktestTaskStatus status, String result, String error) {
        BacktestTask t = new BacktestTask();
        t.setId(id);
        t.setStatus(status);
        t.setResult(result);
        t.setErrorMessage(error);
        return t;
    }

    private static StrategyDefinition strategy(long id, String exchange, StrategyStatus status) {
        StrategyDefinition s = new StrategyDefinition();
        s.setId(id);
        s.setExchange(exchange);
        s.setStatus(status);
        s.setName("s" + id);
        s.setIntervalValue("1h");
        return s;
    }

    private static ExchangeAccount account(long id, Exchange exchange, boolean paperTrading) {
        ExchangeAccount a = new ExchangeAccount();
        a.setId(id);
        a.setUserId(42L);
        a.setExchange(exchange);
        a.setPaperTrading(paperTrading);
        return a;
    }
}
