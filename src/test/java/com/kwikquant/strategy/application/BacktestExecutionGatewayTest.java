package com.kwikquant.strategy.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.kwikquant.market.application.FundingCoverageGuard;
import com.kwikquant.market.application.TradingPairService;
import com.kwikquant.market.domain.TradingPairInfo;
import com.kwikquant.report.application.ReportService;
import com.kwikquant.shared.infra.WorkerTokenService;
import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.MarketType;
import com.kwikquant.strategy.domain.BacktestFailureCategory;
import com.kwikquant.strategy.domain.BacktestNoMarketDataException;
import com.kwikquant.strategy.domain.BacktestTask;
import com.kwikquant.strategy.domain.BacktestTaskStatus;
import com.kwikquant.strategy.domain.StrategyCode;
import com.kwikquant.strategy.infrastructure.BacktestTaskMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Gateway 单测。撮合本地化后无虚拟账本:initLedger/cleanupLedger 及 initial_capital
 * 解析(现归 Python worker)测试随之移除;matchingConfig 快照断言 {@link BacktestExecutionGateway#defaultMatchingConfig()}。
 */
class BacktestExecutionGatewayTest {

    private BacktestTaskMapper taskMapper;
    private SimpMessagingTemplate ws;
    private ObjectMapper objectMapper;
    private WorkerTokenService tokenService;
    private ReportService reportService;
    private StrategyCodeService codeService;
    private TradingPairService tradingPairService;
    private FundingCoverageGuard fundingCoverageGuard;

    @BeforeEach
    void setUp() {
        taskMapper = mock(BacktestTaskMapper.class);
        ws = mock(SimpMessagingTemplate.class);
        objectMapper = new ObjectMapper();
        tokenService = mock(WorkerTokenService.class);
        reportService = mock(ReportService.class);
        codeService = mock(StrategyCodeService.class);
        tradingPairService = mock(TradingPairService.class);
        fundingCoverageGuard = mock(FundingCoverageGuard.class);
        when(codeService.getOwnedCode(anyLong(), anyLong(), anyLong())).thenReturn(code());
    }

    private BacktestExecutionGateway gatewayWithRunner(BacktestRunner runner) {
        return new BacktestExecutionGateway(
                taskMapper,
                runner,
                ws,
                objectMapper,
                tokenService,
                reportService,
                codeService,
                tradingPairService,
                fundingCoverageGuard);
    }

    @Test
    void executeAsync_casConflictSkips_noTokenIssued() {
        when(taskMapper.findById(1L)).thenReturn(task(1L, 42L));
        when(taskMapper.updateStatus(1L, 42L, "PENDING", "RUNNING")).thenReturn(0);
        var gateway = gatewayWithRunner(mock(BacktestRunner.class));

        gateway.executeAsync(1L);

        verify(taskMapper, never()).updateError(anyLong(), anyLong(), anyString(), anyString());
        verify(taskMapper, never()).updateResult(anyLong(), anyLong(), anyString(), any());
        verify(ws, never()).convertAndSend(anyString(), any(Object.class));
        verify(tokenService, never()).issueBacktestToken(anyLong(), anyLong(), anyLong(), anyString());
    }

    @Test
    void executeAsync_taskNotFoundSkips() {
        when(taskMapper.findById(1L)).thenReturn(null);
        var gateway = gatewayWithRunner(mock(BacktestRunner.class));

        gateway.executeAsync(1L);

        verify(taskMapper, never()).updateStatus(anyLong(), anyLong(), anyString(), anyString());
        verify(taskMapper, never()).updateError(anyLong(), anyLong(), anyString(), anyString());
        verify(tokenService, never()).issueBacktestToken(anyLong(), anyLong(), anyLong(), anyString());
    }

    @Test
    void executeAsync_withRunner_happyPath_tokenIssueReportUpdateRevoke() {
        when(taskMapper.findById(1L)).thenReturn(task(1L, 42L));
        when(taskMapper.updateStatus(1L, 42L, "PENDING", "RUNNING")).thenReturn(1);
        when(tokenService.issueBacktestToken(anyLong(), anyLong(), anyLong(), anyString()))
                .thenReturn("tk-abc");
        BacktestRunner runner = mock(BacktestRunner.class);
        String s8 =
                "{\"trades\":[{\"time\":\"2024-01-15T08:00:00Z\",\"side\":\"buy\",\"price\":42150,\"amount\":0.1,\"fee\":4.215}],\"equity_curve\":[{\"time\":\"2024-01-01\",\"equity\":10000},{\"time\":\"2024-01-02\",\"equity\":10023.5}]}";
        when(runner.run(any())).thenReturn(new BacktestResult(new BigDecimal("23.5"), 1, s8));
        when(reportService.submitBacktestResult(42L, s8)).thenReturn(99L);
        var gateway = gatewayWithRunner(runner);

        gateway.executeAsync(1L);

        InOrder inOrder = inOrder(tokenService, codeService, runner, reportService, taskMapper, ws);
        inOrder.verify(tokenService).issueBacktestToken(eq(5L), eq(1L), eq(42L), anyString());
        inOrder.verify(codeService).getOwnedCode(anyLong(), anyLong(), anyLong());
        ArgumentCaptor<BacktestRunRequest> reqCap = ArgumentCaptor.forClass(BacktestRunRequest.class);
        inOrder.verify(runner).run(reqCap.capture());
        assertTrue(
                reqCap.getValue().strategySource().contains("on_bar"), "strategySource 应传入 worker 供其 exec 取顶层 on_bar");
        // matchingConfig = defaultMatchingConfig():FAST + 5bps + maker/taker 费率(与 Python 引擎契约一致)
        Map<String, Object> mc = reqCap.getValue().matchingConfig();
        assertEquals("FAST", mc.get("fidelity"));
        assertEquals("5", mc.get("marketSlippageBps"));
        assertEquals("0.001", mc.get("makerFeeRate"));
        assertEquals("0.002", mc.get("takerFeeRate"));
        inOrder.verify(reportService).submitBacktestResult(42L, s8);
        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        inOrder.verify(taskMapper).updateResult(eq(1L), eq(42L), jsonCaptor.capture(), eq(99L));
        inOrder.verify(ws)
                .convertAndSend(
                        eq("/topic/backtests/42"),
                        argThat((Object o) -> o instanceof Map<?, ?> m && "COMPLETED".equals(m.get("status"))));
        inOrder.verify(tokenService).revokeToken("tk-abc");

        String json = jsonCaptor.getValue();
        assertTrue(json.contains("totalPnl"), "result JSON should contain totalPnl: " + json);
        assertTrue(json.contains("23.5"), "result JSON should contain totalPnl value: " + json);
        verify(taskMapper, never()).updateError(anyLong(), anyLong(), anyString(), anyString());
    }

    @Test
    void executeAsync_runnerThrowsNoMarketData_marksFailed_finallyRevokes() {
        // worker 拉空 → Runner 抛 BacktestNoMarketDataException → Gateway catch markFailed
        // (errorMessage 含区间信息,非 generic 7300);finally revoke token
        when(taskMapper.findById(1L)).thenReturn(task(1L, 42L));
        when(taskMapper.updateStatus(1L, 42L, "PENDING", "RUNNING")).thenReturn(1);
        when(tokenService.issueBacktestToken(anyLong(), anyLong(), anyLong(), anyString()))
                .thenReturn("tk-nm");
        BacktestRunner runner = mock(BacktestRunner.class);
        when(runner.run(any())).thenThrow(new BacktestNoMarketDataException("OKX SPOT BTC/USDT 无历史数据"));
        var gateway = gatewayWithRunner(runner);

        gateway.executeAsync(1L);

        verify(taskMapper).updateError(1L, 42L, "OKX SPOT BTC/USDT 无历史数据", "MARKET_DATA");
        verify(taskMapper, never()).updateResult(anyLong(), anyLong(), anyString(), any());
        verify(reportService, never()).submitBacktestResult(anyLong(), anyString());
        verify(tokenService).revokeToken("tk-nm");
        // FAILED WS 事件带 category + userMessage(产品文案):前端 toast 优先用户可读文案,不裸透 stderr
        verify(ws)
                .convertAndSend(
                        eq("/topic/backtests/42"),
                        argThat((Object o) -> o instanceof Map<?, ?> m
                                && "FAILED".equals(m.get("status"))
                                && "MARKET_DATA".equals(m.get("category"))
                                && BacktestFailureCategory.MARKET_DATA
                                        .userMessage()
                                        .equals(m.get("userMessage"))));
    }

    @Test
    void executeAsync_runnerThrows_markFailed_finallyRevokes() {
        when(taskMapper.findById(1L)).thenReturn(task(1L, 42L));
        when(taskMapper.updateStatus(1L, 42L, "PENDING", "RUNNING")).thenReturn(1);
        when(tokenService.issueBacktestToken(anyLong(), anyLong(), anyLong(), anyString()))
                .thenReturn("tk-xyz");
        BacktestRunner runner = mock(BacktestRunner.class);
        when(runner.run(any())).thenThrow(new RuntimeException("worker crashed"));
        var gateway = gatewayWithRunner(runner);

        gateway.executeAsync(1L);

        verify(taskMapper).updateError(1L, 42L, "worker crashed", "INTERNAL");
        verify(taskMapper, never()).updateResult(anyLong(), anyLong(), anyString(), any());
        // finally 必须 revoke:防 token 泄露(C4/R6)
        verify(tokenService).revokeToken("tk-xyz");
        verify(reportService, never()).submitBacktestResult(anyLong(), anyString());
    }

    @Test
    void executeAsync_runnerThrowsWithoutMessage_usesClassSimpleName_finallyRevokes() {
        when(taskMapper.findById(1L)).thenReturn(task(1L, 42L));
        when(taskMapper.updateStatus(1L, 42L, "PENDING", "RUNNING")).thenReturn(1);
        when(tokenService.issueBacktestToken(anyLong(), anyLong(), anyLong(), anyString()))
                .thenReturn("tk-1");
        BacktestRunner runner = mock(BacktestRunner.class);
        when(runner.run(any())).thenThrow(new NullPointerException());
        var gateway = gatewayWithRunner(runner);

        gateway.executeAsync(1L);

        verify(taskMapper).updateError(1L, 42L, "NullPointerException", "INTERNAL");
        verify(tokenService).revokeToken("tk-1");
    }

    @Test
    void executeAsync_perpTask_fundingRecheckFails_marksFailedWithoutRunningWorker() {
        // 快照语义(V54):PERP 判断以任务 market_type 快照为准;执行前复查资金费覆盖度,
        // 排队期间数据变化(采集停摆/被删)→ markFailed 不跑 worker
        BacktestTask perpTask = perpTask(1L, 42L);
        when(taskMapper.findById(1L)).thenReturn(perpTask);
        when(taskMapper.updateStatus(1L, 42L, "PENDING", "RUNNING")).thenReturn(1);
        when(tokenService.issueBacktestToken(anyLong(), anyLong(), anyLong(), anyString()))
                .thenReturn("tk-perp");
        when(fundingCoverageGuard.ensureCoverage(any(), any(), any(), any(), anyBoolean(), any()))
                .thenThrow(new IllegalArgumentException("PERP 回测资金费序列不完整"));
        BacktestRunner runner = mock(BacktestRunner.class);

        gatewayWithRunner(runner).executeAsync(1L);

        verify(runner, never()).run(any());
        verify(reportService, never()).submitBacktestResult(anyLong(), anyString());
        verify(taskMapper).updateError(eq(1L), eq(42L), contains("资金费序列不完整"), eq("FUNDING_DATA"));
        verify(tokenService).revokeToken("tk-perp");
    }

    @Test
    void executeAsync_perpTask_happyPath_recheckAndPairSpecsDelivered() {
        BacktestTask perpTask = perpTask(1L, 42L);
        when(taskMapper.findById(1L)).thenReturn(perpTask);
        when(taskMapper.updateStatus(1L, 42L, "PENDING", "RUNNING")).thenReturn(1);
        when(tokenService.issueBacktestToken(anyLong(), anyLong(), anyLong(), anyString()))
                .thenReturn("tk-perp2");
        when(tradingPairService.getPairs(Exchange.BINANCE, MarketType.PERP)).thenReturn(List.of(perpPairInfo()));
        BacktestRunner runner = mock(BacktestRunner.class);
        String s8 = "{\"trades\":[],\"equity_curve\":[{\"time\":\"t\",\"equity\":\"100000\"}]}";
        when(runner.run(any())).thenReturn(new BacktestResult(BigDecimal.ZERO, 0, s8));
        when(reportService.submitBacktestResult(42L, s8)).thenReturn(88L);

        gatewayWithRunner(runner).executeAsync(1L);

        // 复查:allowProxy=false(代理是提交时的显式决定,执行期不自动扩权)
        verify(fundingCoverageGuard)
                .ensureCoverage(eq(Exchange.BINANCE), eq("BTC/USDT"), any(), any(), eq(false), any(Instant.class));
        ArgumentCaptor<BacktestRunRequest> reqCap = ArgumentCaptor.forClass(BacktestRunRequest.class);
        verify(runner).run(reqCap.capture());
        BacktestRunRequest.PairSpecSnapshot spec = reqCap.getValue().pairSpecs().get("BTC/USDT");
        assertNotNull(spec);
        assertEquals("0.001", spec.minQty()); // 币单位、toPlainString 字符串
        assertEquals("0.001", spec.stepSize());
        assertEquals("0.1", spec.tickSize());
        assertEquals(100, spec.maxLeverage());
        assertNull(spec.maxQty());
        assertEquals("PERP", spec.marketType());
        verify(tokenService).revokeToken("tk-perp2");
    }

    @Test
    void executeAsync_perpTask_pairSpecMissing_marksFailed() {
        // PERP fail-closed:装载结果缺任务 symbol → 拒执行(接受性闸门没有规格无法拒非法单)
        BacktestTask perpTask = perpTask(1L, 42L);
        when(taskMapper.findById(1L)).thenReturn(perpTask);
        when(taskMapper.updateStatus(1L, 42L, "PENDING", "RUNNING")).thenReturn(1);
        when(tokenService.issueBacktestToken(anyLong(), anyLong(), anyLong(), anyString()))
                .thenReturn("tk-perp3");
        when(tradingPairService.getPairs(Exchange.BINANCE, MarketType.PERP)).thenReturn(List.of());
        BacktestRunner runner = mock(BacktestRunner.class);

        gatewayWithRunner(runner).executeAsync(1L);

        verify(runner, never()).run(any());
        verify(taskMapper).updateError(eq(1L), eq(42L), contains("无规格快照"), anyString());
        verify(tokenService).revokeToken("tk-perp3");
    }

    @Test
    void executeAsync_perpPortfolioTask_defensivelyMarksFailed() {
        // 提交入口已拒 PERP 组合;防御历史存量/异常快照
        BacktestTask t = BacktestTask.create(
                5L,
                42L,
                5L,
                null,
                List.of("BTC/USDT", "ETH/USDT"),
                "BINANCE",
                "PERP",
                "1h",
                Instant.now(),
                Instant.now(),
                "{}");
        t.setId(1L);
        t.setStatus(BacktestTaskStatus.PENDING);
        when(taskMapper.findById(1L)).thenReturn(t);
        when(taskMapper.updateStatus(1L, 42L, "PENDING", "RUNNING")).thenReturn(1);
        when(tokenService.issueBacktestToken(anyLong(), anyLong(), anyLong(), anyString()))
                .thenReturn("tk-perp4");
        BacktestRunner runner = mock(BacktestRunner.class);

        gatewayWithRunner(runner).executeAsync(1L);

        verify(runner, never()).run(any());
        verify(taskMapper).updateError(eq(1L), eq(42L), contains("PERP 组合回测暂不支持"), anyString());
    }

    @Test
    void executeAsync_perpWorkerFundingMissing_marksFailedFundingData() {
        // worker 运行期缺期检测(exit 3)→ Runner 抛 BacktestFundingDataMissingException
        // → markFailed(分类 FUNDING_DATA,专属文案给"缩短区间/开资金费代理"出路)+ finally revoke
        BacktestTask perpTask = perpTask(1L, 42L);
        when(taskMapper.findById(1L)).thenReturn(perpTask);
        when(taskMapper.updateStatus(1L, 42L, "PENDING", "RUNNING")).thenReturn(1);
        when(tokenService.issueBacktestToken(anyLong(), anyLong(), anyLong(), anyString()))
                .thenReturn("tk-f");
        when(tradingPairService.getPairs(Exchange.BINANCE, MarketType.PERP)).thenReturn(List.of(perpPairInfo()));
        BacktestRunner runner = mock(BacktestRunner.class);
        when(runner.run(any()))
                .thenThrow(new com.kwikquant.strategy.domain.BacktestFundingDataMissingException(
                        "OKX BTC/USDT 资金费序列缺期: 首缺 2025-01-05T08:00:00Z"));

        gatewayWithRunner(runner).executeAsync(1L);

        verify(taskMapper).updateError(eq(1L), eq(42L), contains("资金费序列缺期"), eq("FUNDING_DATA"));
        verify(reportService, never()).submitBacktestResult(anyLong(), anyString());
        verify(tokenService).revokeToken("tk-f");
    }

    @Test
    void executeAsync_spotTask_pairLoadFails_proceedsWithEmptySpecs() {
        // SPOT 宽松:CCXT 装载失败 → 空快照继续跑(worker 无快照跳过 acceptance = 存量行为)
        when(taskMapper.findById(1L)).thenReturn(task(1L, 42L));
        when(taskMapper.updateStatus(1L, 42L, "PENDING", "RUNNING")).thenReturn(1);
        when(tokenService.issueBacktestToken(anyLong(), anyLong(), anyLong(), anyString()))
                .thenReturn("tk-s");
        when(tradingPairService.getPairs(Exchange.BINANCE, MarketType.SPOT))
                .thenThrow(new RuntimeException("ccxt down"));
        BacktestRunner runner = mock(BacktestRunner.class);
        String s8 = "{\"trades\":[],\"equity_curve\":[{\"time\":\"t\",\"equity\":\"10000\"}]}";
        when(runner.run(any())).thenReturn(new BacktestResult(BigDecimal.ZERO, 0, s8));
        when(reportService.submitBacktestResult(42L, s8)).thenReturn(66L);

        gatewayWithRunner(runner).executeAsync(1L);

        ArgumentCaptor<BacktestRunRequest> reqCap = ArgumentCaptor.forClass(BacktestRunRequest.class);
        verify(runner).run(reqCap.capture());
        assertTrue(reqCap.getValue().pairSpecs().isEmpty());
        verify(fundingCoverageGuard, never()).ensureCoverage(any(), any(), any(), any(), anyBoolean(), any());
        verify(taskMapper, never()).updateError(anyLong(), anyLong(), anyString(), anyString());
    }

    @Test
    void executeAsync_spotTask_pairSpecsDeliveredWhenAvailable() {
        when(taskMapper.findById(1L)).thenReturn(task(1L, 42L));
        when(taskMapper.updateStatus(1L, 42L, "PENDING", "RUNNING")).thenReturn(1);
        when(tokenService.issueBacktestToken(anyLong(), anyLong(), anyLong(), anyString()))
                .thenReturn("tk-s2");
        when(tradingPairService.getPairs(Exchange.BINANCE, MarketType.SPOT)).thenReturn(List.of(spotPairInfo()));
        BacktestRunner runner = mock(BacktestRunner.class);
        String s8 = "{\"trades\":[],\"equity_curve\":[{\"time\":\"t\",\"equity\":\"10000\"}]}";
        when(runner.run(any())).thenReturn(new BacktestResult(BigDecimal.ZERO, 0, s8));
        when(reportService.submitBacktestResult(42L, s8)).thenReturn(67L);

        gatewayWithRunner(runner).executeAsync(1L);

        ArgumentCaptor<BacktestRunRequest> reqCap = ArgumentCaptor.forClass(BacktestRunRequest.class);
        verify(runner).run(reqCap.capture());
        BacktestRunRequest.PairSpecSnapshot spec = reqCap.getValue().pairSpecs().get("BTC/USDT");
        assertNotNull(spec);
        assertEquals("SPOT", spec.marketType());
        assertNull(spec.maxLeverage()); // SPOT 恒 null(装载语义)
        // contractSize 不入快照:张数是交易所边界概念(ArchUnit 强制),回测域内全币单位无消费方
    }

    private BacktestTask perpTask(long id, long userId) {
        BacktestTask t = task(id, userId);
        t.setMarketType("PERP");
        return t;
    }

    private static TradingPairInfo perpPairInfo() {
        return new TradingPairInfo(
                Exchange.BINANCE,
                MarketType.PERP,
                "BTC/USDT",
                "BTC/USDT:USDT",
                "BTC",
                "USDT",
                new BigDecimal("0.001"),
                null,
                new BigDecimal("0.1"),
                new BigDecimal("0.001"),
                new BigDecimal("0.01"),
                true,
                100);
    }

    private static TradingPairInfo spotPairInfo() {
        return new TradingPairInfo(
                Exchange.BINANCE,
                MarketType.SPOT,
                "BTC/USDT",
                "BTC",
                "USDT",
                new BigDecimal("0.00001"),
                null,
                new BigDecimal("0.1"),
                new BigDecimal("0.00001"),
                true);
    }

    @Test
    void executeAsync_reportServiceThrows_marksFailed_finallyRevokes() {
        when(taskMapper.findById(1L)).thenReturn(task(1L, 42L));
        when(taskMapper.updateStatus(1L, 42L, "PENDING", "RUNNING")).thenReturn(1);
        when(tokenService.issueBacktestToken(anyLong(), anyLong(), anyLong(), anyString()))
                .thenReturn("tk-2");
        BacktestRunner runner = mock(BacktestRunner.class);
        when(runner.run(any())).thenReturn(new BacktestResult(BigDecimal.TEN, 5, "{\"trades\":[]}"));
        doThrow(new RuntimeException("trades empty")).when(reportService).submitBacktestResult(anyLong(), anyString());
        var gateway = gatewayWithRunner(runner);

        gateway.executeAsync(1L);

        verify(taskMapper).updateError(1L, 42L, "trades empty", "INTERNAL");
        verify(tokenService).revokeToken("tk-2");
        verify(taskMapper, never()).updateResult(anyLong(), anyLong(), anyString(), any());
    }

    @Test
    void markFailedByRecovery_runningTask_transitionsAndBroadcasts() {
        var gateway = gatewayWithRunner(mock(BacktestRunner.class));
        when(taskMapper.updateError(eq(9L), eq(42L), eq("服务重启，回测任务中断，请重新提交"), anyString()))
                .thenReturn(1);

        boolean failed = gateway.markFailedByRecovery(9L, 42L, "服务重启，回测任务中断，请重新提交");

        assertTrue(failed);
        // 恢复路径与正常失败路径同构:事件带 category(此处 INTERNAL)+ error;INTERNAL 不带 userMessage
        verify(ws)
                .convertAndSend(
                        eq("/topic/backtests/42"),
                        argThat((Object o) -> o instanceof Map<?, ?> m
                                && "FAILED".equals(m.get("status"))
                                && "INTERNAL".equals(m.get("category"))
                                && !m.containsKey("userMessage")));
    }

    @Test
    void markFailedByRecovery_taskAlreadyTerminal_returnsFalseNoBroadcast() {
        // updateError 带 status='RUNNING' 守卫:已终态任务返 0 → 不重复广播
        var gateway = gatewayWithRunner(mock(BacktestRunner.class));
        when(taskMapper.updateError(anyLong(), anyLong(), anyString(), anyString()))
                .thenReturn(0);

        boolean failed = gateway.markFailedByRecovery(9L, 42L, "reason");

        assertFalse(failed);
        verify(ws, never()).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    void defaultMatchingConfig_matchesPythonEngineDefaults() {
        // 跨语言契约:与 kwikquant_worker/backtest/matching.py MatchConfig.defaults() 一致(spec §2)
        Map<String, Object> mc = BacktestExecutionGateway.defaultMatchingConfig();
        assertEquals("FAST", mc.get("fidelity"));
        assertEquals("5", mc.get("marketSlippageBps"));
        assertEquals(false, mc.get("partialFillEnabled"));
        assertEquals("0.001", mc.get("makerFeeRate"));
        assertEquals("0.002", mc.get("takerFeeRate"));
    }

    private static StrategyCode code() {
        // 当前 worker 契约:顶层 def on_bar(bar, ctx)(旧 Strategy 子类形态已废弃)
        StrategyCode c = new StrategyCode();
        c.setSourceCode("def on_bar(bar, ctx):\n    pass\n");
        return c;
    }

    private BacktestTask task(long id, long userId) {
        BacktestTask t = BacktestTask.create(
                5L, userId, 5L, "BTC/USDT", "BINANCE", "SPOT", "1h", Instant.now(), Instant.now(), "{}");
        t.setId(id);
        t.setStatus(BacktestTaskStatus.PENDING);
        return t;
    }

    @Test
    void executeAsync_portfolioTask_passesSymbolsToRunner() {
        when(taskMapper.findById(1L)).thenReturn(portfolioTask(1L, 42L));
        when(taskMapper.updateStatus(1L, 42L, "PENDING", "RUNNING")).thenReturn(1);
        StrategyCode portfolioCode = new StrategyCode();
        portfolioCode.setSourceCode("def on_bars(ctx):\n    pass\n");
        when(codeService.getOwnedCode(anyLong(), anyLong(), anyLong())).thenReturn(portfolioCode);
        when(tokenService.issueBacktestToken(anyLong(), anyLong(), anyLong(), anyString()))
                .thenReturn("tk-p");
        BacktestRunner runner = mock(BacktestRunner.class);
        String s8 =
                "{\"trades\":[],\"equity_curve\":[{\"time\":\"2024-01-01\",\"equity\":10000},{\"time\":\"2024-01-02\",\"equity\":10000}]}";
        when(runner.run(any())).thenReturn(new BacktestResult(BigDecimal.ZERO, 0, s8));
        when(reportService.submitBacktestResult(42L, s8)).thenReturn(77L);
        var gateway = gatewayWithRunner(runner);

        gateway.executeAsync(1L);

        ArgumentCaptor<BacktestRunRequest> reqCap = ArgumentCaptor.forClass(BacktestRunRequest.class);
        verify(runner).run(reqCap.capture());
        assertEquals(
                List.of("BTC/USDT", "ETH/USDT", "SOL/USDT"), reqCap.getValue().symbols());
        assertNull(reqCap.getValue().symbol());
        assertTrue(reqCap.getValue().strategySource().contains("on_bars"));
    }

    private BacktestTask portfolioTask(long id, long userId) {
        BacktestTask t = BacktestTask.create(
                5L,
                userId,
                5L,
                null,
                List.of("BTC/USDT", "ETH/USDT", "SOL/USDT"),
                "BINANCE",
                "SPOT",
                "1h",
                Instant.now(),
                Instant.now(),
                "{}");
        t.setId(id);
        t.setStatus(BacktestTaskStatus.PENDING);
        return t;
    }
}
