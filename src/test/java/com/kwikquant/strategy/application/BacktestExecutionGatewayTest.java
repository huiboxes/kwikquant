package com.kwikquant.strategy.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.kwikquant.report.application.ReportService;
import com.kwikquant.shared.infra.BacktestLedgerLifecycle;
import com.kwikquant.shared.infra.WorkerTokenService;
import com.kwikquant.strategy.domain.BacktestTask;
import com.kwikquant.strategy.domain.BacktestTaskStatus;
import com.kwikquant.strategy.infrastructure.BacktestTaskMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import tools.jackson.databind.ObjectMapper;

class BacktestExecutionGatewayTest {

    private BacktestTaskMapper taskMapper;
    private SimpMessagingTemplate ws;
    private ObjectMapper objectMapper;
    private WorkerTokenService tokenService;
    private BacktestLedgerLifecycle ledger;
    private ReportService reportService;

    @BeforeEach
    void setUp() {
        taskMapper = mock(BacktestTaskMapper.class);
        ws = mock(SimpMessagingTemplate.class);
        objectMapper = new ObjectMapper();
        tokenService = mock(WorkerTokenService.class);
        ledger = mock(BacktestLedgerLifecycle.class);
        reportService = mock(ReportService.class);
    }

    private BacktestExecutionGateway gatewayWithRunner(BacktestRunner runner) {
        return new BacktestExecutionGateway(
                taskMapper,
                Optional.ofNullable(runner),
                ws,
                objectMapper,
                tokenService,
                ledger,
                reportService);
    }

    @Test
    void executeAsync_noRunner_marksFailedAndNoTokenOrLedger() {
        when(taskMapper.findById(1L)).thenReturn(task(1L, 42L));
        when(taskMapper.updateStatus(1L, 42L, "PENDING", "RUNNING")).thenReturn(1);
        var gateway = gatewayWithRunner(null);

        gateway.executeAsync(1L);

        verify(taskMapper).updateError(1L, 42L, BacktestExecutionGateway.STUB_MESSAGE);
        verify(ws)
                .convertAndSend(
                        eq("/topic/backtests/42"),
                        argThat((Object o) -> o instanceof Map<?, ?> m && "FAILED".equals(m.get("status"))));
        verify(taskMapper, never()).updateResult(anyLong(), anyLong(), anyString());
        verify(tokenService, never()).issueToken(anyLong(), anyString(), anyLong(), anyString());
        verify(ledger, never()).initLedger(anyLong(), any());
        verify(ledger, never()).cleanupLedger(anyLong());
    }

    @Test
    void executeAsync_casConflictSkips_noTokenOrLedger() {
        when(taskMapper.findById(1L)).thenReturn(task(1L, 42L));
        when(taskMapper.updateStatus(1L, 42L, "PENDING", "RUNNING")).thenReturn(0);
        var gateway = gatewayWithRunner(mock(BacktestRunner.class));

        gateway.executeAsync(1L);

        verify(taskMapper, never()).updateError(anyLong(), anyLong(), anyString());
        verify(taskMapper, never()).updateResult(anyLong(), anyLong(), anyString());
        verify(ws, never()).convertAndSend(anyString(), any(Object.class));
        verify(tokenService, never()).issueToken(anyLong(), anyString(), anyLong(), anyString());
        verify(ledger, never()).initLedger(anyLong(), any());
    }

    @Test
    void executeAsync_taskNotFoundSkips() {
        when(taskMapper.findById(1L)).thenReturn(null);
        var gateway = gatewayWithRunner(mock(BacktestRunner.class));

        gateway.executeAsync(1L);

        verify(taskMapper, never()).updateStatus(anyLong(), anyLong(), anyString(), anyString());
        verify(taskMapper, never()).updateError(anyLong(), anyLong(), anyString());
        verify(tokenService, never()).issueToken(anyLong(), anyString(), anyLong(), anyString());
        verify(ledger, never()).initLedger(anyLong(), any());
    }

    @Test
    void executeAsync_withRunner_happyPath_ordersTokenIssueLedgerInitReportUpdateCleanupRevoke() {
        when(taskMapper.findById(1L)).thenReturn(task(1L, 42L));
        when(taskMapper.updateStatus(1L, 42L, "PENDING", "RUNNING")).thenReturn(1);
        when(tokenService.issueToken(anyLong(), anyString(), anyLong(), anyString())).thenReturn("tk-abc");
        BacktestRunner runner = mock(BacktestRunner.class);
        String s8 = "{\"trades\":[{\"time\":\"2024-01-15T08:00:00Z\",\"side\":\"buy\",\"price\":42150,\"amount\":0.1,\"fee\":4.215}],\"equity_curve\":[{\"time\":\"2024-01-01\",\"equity\":10000},{\"time\":\"2024-01-02\",\"equity\":10023.5}]}";
        when(runner.run(any())).thenReturn(new BacktestResult(new BigDecimal("23.5"), 1, s8));
        var gateway = gatewayWithRunner(runner);

        gateway.executeAsync(1L);

        InOrder inOrder = inOrder(tokenService, ledger, runner, reportService, taskMapper, ws);
        inOrder.verify(tokenService).issueToken(eq(5L), eq("BACKTEST"), eq(42L), anyString());
        inOrder.verify(ledger).initLedger(eq(1L), any(BigDecimal.class));
        inOrder.verify(runner).run(any());
        inOrder.verify(reportService).submitBacktestResult(42L, s8);
        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        inOrder.verify(taskMapper).updateResult(eq(1L), eq(42L), jsonCaptor.capture());
        inOrder.verify(ws)
                .convertAndSend(
                        eq("/topic/backtests/42"),
                        argThat((Object o) -> o instanceof Map<?, ?> m && "COMPLETED".equals(m.get("status"))));
        inOrder.verify(ledger).cleanupLedger(1L);
        inOrder.verify(tokenService).revokeToken("tk-abc");

        String json = jsonCaptor.getValue();
        assertTrue(json.contains("realizedPnl"), "result JSON should contain realizedPnl: " + json);
        assertTrue(json.contains("23.5"), "result JSON should contain realizedPnl value: " + json);
        verify(taskMapper, never()).updateError(anyLong(), anyLong(), anyString());
    }

    @Test
    void executeAsync_runnerThrows_markFailed_finallyCleansUp() {
        when(taskMapper.findById(1L)).thenReturn(task(1L, 42L));
        when(taskMapper.updateStatus(1L, 42L, "PENDING", "RUNNING")).thenReturn(1);
        when(tokenService.issueToken(anyLong(), anyString(), anyLong(), anyString())).thenReturn("tk-xyz");
        BacktestRunner runner = mock(BacktestRunner.class);
        when(runner.run(any())).thenThrow(new RuntimeException("worker crashed"));
        var gateway = gatewayWithRunner(runner);

        gateway.executeAsync(1L);

        verify(taskMapper).updateError(1L, 42L, "worker crashed");
        verify(taskMapper, never()).updateResult(anyLong(), anyLong(), anyString());
        // finally 必须清理:防内存泄漏 + token 泄露(C4/R6)
        verify(ledger).cleanupLedger(1L);
        verify(tokenService).revokeToken("tk-xyz");
        verify(reportService, never()).submitBacktestResult(anyLong(), anyString());
    }

    @Test
    void executeAsync_runnerThrowsWithoutMessage_usesClassSimpleName_finallyCleansUp() {
        when(taskMapper.findById(1L)).thenReturn(task(1L, 42L));
        when(taskMapper.updateStatus(1L, 42L, "PENDING", "RUNNING")).thenReturn(1);
        when(tokenService.issueToken(anyLong(), anyString(), anyLong(), anyString())).thenReturn("tk-1");
        BacktestRunner runner = mock(BacktestRunner.class);
        when(runner.run(any())).thenThrow(new NullPointerException());
        var gateway = gatewayWithRunner(runner);

        gateway.executeAsync(1L);

        verify(taskMapper).updateError(1L, 42L, "NullPointerException");
        verify(ledger).cleanupLedger(1L);
        verify(tokenService).revokeToken("tk-1");
    }

    @Test
    void executeAsync_reportServiceThrows_marksFailed_finallyCleansUp() {
        when(taskMapper.findById(1L)).thenReturn(task(1L, 42L));
        when(taskMapper.updateStatus(1L, 42L, "PENDING", "RUNNING")).thenReturn(1);
        when(tokenService.issueToken(anyLong(), anyString(), anyLong(), anyString())).thenReturn("tk-2");
        BacktestRunner runner = mock(BacktestRunner.class);
        when(runner.run(any())).thenReturn(new BacktestResult(BigDecimal.TEN, 5, "{\"trades\":[]}"));
        doThrow(new RuntimeException("trades empty"))
                .when(reportService)
                .submitBacktestResult(anyLong(), anyString());
        var gateway = gatewayWithRunner(runner);

        gateway.executeAsync(1L);

        verify(taskMapper).updateError(1L, 42L, "trades empty");
        verify(ledger).cleanupLedger(1L);
        verify(tokenService).revokeToken("tk-2");
        verify(taskMapper, never()).updateResult(anyLong(), anyLong(), anyString());
    }

    @Test
    void executeAsync_initialCapital_parsedFromParametersJson() {
        BacktestTask t = task(2L, 42L);
        t.setParameters("{\"initial_capital\":\"50000\"}");
        when(taskMapper.findById(2L)).thenReturn(t);
        when(taskMapper.updateStatus(2L, 42L, "PENDING", "RUNNING")).thenReturn(1);
        when(tokenService.issueToken(anyLong(), anyString(), anyLong(), anyString())).thenReturn("tk-3");
        BacktestRunner runner = mock(BacktestRunner.class);
        when(runner.run(any())).thenReturn(new BacktestResult(BigDecimal.ZERO, 0, "{\"trades\":[],\"equity_curve\":[]}"));
        var gateway = gatewayWithRunner(runner);

        gateway.executeAsync(2L);

        ArgumentCaptor<BigDecimal> capCap = ArgumentCaptor.forClass(BigDecimal.class);
        verify(ledger).initLedger(eq(2L), capCap.capture());
        assertEquals(0, new BigDecimal("50000").compareTo(capCap.getValue()));
    }

    @Test
    void executeAsync_initialCapital_defaults_whenParametersMalformed() {
        BacktestTask t = task(3L, 42L);
        t.setParameters("not-a-json");
        when(taskMapper.findById(3L)).thenReturn(t);
        when(taskMapper.updateStatus(3L, 42L, "PENDING", "RUNNING")).thenReturn(1);
        when(tokenService.issueToken(anyLong(), anyString(), anyLong(), anyString())).thenReturn("tk-4");
        BacktestRunner runner = mock(BacktestRunner.class);
        when(runner.run(any())).thenReturn(new BacktestResult(BigDecimal.ZERO, 0, "{\"trades\":[],\"equity_curve\":[]}"));
        var gateway = gatewayWithRunner(runner);

        gateway.executeAsync(3L);

        ArgumentCaptor<BigDecimal> capCap = ArgumentCaptor.forClass(BigDecimal.class);
        verify(ledger).initLedger(eq(3L), capCap.capture());
        assertEquals(0, new BigDecimal("100000").compareTo(capCap.getValue()));
    }

    @Test
    void executeAsync_initialCapital_defaults_whenParametersBlank() {
        BacktestTask t = task(4L, 42L);
        t.setParameters("");
        when(taskMapper.findById(4L)).thenReturn(t);
        when(taskMapper.updateStatus(4L, 42L, "PENDING", "RUNNING")).thenReturn(1);
        when(tokenService.issueToken(anyLong(), anyString(), anyLong(), anyString())).thenReturn("tk-5");
        BacktestRunner runner = mock(BacktestRunner.class);
        when(runner.run(any())).thenReturn(new BacktestResult(BigDecimal.ZERO, 0, "{\"trades\":[],\"equity_curve\":[]}"));
        var gateway = gatewayWithRunner(runner);

        gateway.executeAsync(4L);

        ArgumentCaptor<BigDecimal> capCap = ArgumentCaptor.forClass(BigDecimal.class);
        verify(ledger).initLedger(eq(4L), capCap.capture());
        assertEquals(0, new BigDecimal("100000").compareTo(capCap.getValue()));
    }

    private BacktestTask task(long id, long userId) {
        BacktestTask t =
                BacktestTask.create(5L, userId, 5L, "BTC/USDT", "BINANCE", "1h", Instant.now(), Instant.now(), "{}");
        t.setId(id);
        t.setStatus(BacktestTaskStatus.PENDING);
        return t;
    }
}
