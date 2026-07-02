package com.kwikquant.strategy.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

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
import org.springframework.messaging.simp.SimpMessagingTemplate;
import tools.jackson.databind.ObjectMapper;

class BacktestExecutionGatewayTest {

    private BacktestTaskMapper taskMapper;
    private SimpMessagingTemplate ws;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        taskMapper = mock(BacktestTaskMapper.class);
        ws = mock(SimpMessagingTemplate.class);
        objectMapper = new ObjectMapper();
    }

    @Test
    void executeAsync_noRunner_marksFailedAndPushes() {
        when(taskMapper.findById(1L)).thenReturn(task(1L, 42L));
        when(taskMapper.updateStatus(1L, 42L, "PENDING", "RUNNING")).thenReturn(1);
        var gateway = new BacktestExecutionGateway(taskMapper, Optional.empty(), ws, objectMapper);

        gateway.executeAsync(1L);

        verify(taskMapper).updateError(1L, 42L, BacktestExecutionGateway.STUB_MESSAGE);
        verify(ws)
                .convertAndSend(
                        eq("/topic/backtests/42"),
                        argThat((Object o) -> o instanceof Map<?, ?> m && "FAILED".equals(m.get("status"))));
        verify(taskMapper, never()).updateResult(anyLong(), anyLong(), anyString());
    }

    @Test
    void executeAsync_casConflictSkips() {
        when(taskMapper.findById(1L)).thenReturn(task(1L, 42L));
        when(taskMapper.updateStatus(1L, 42L, "PENDING", "RUNNING")).thenReturn(0);
        var gateway = new BacktestExecutionGateway(taskMapper, Optional.empty(), ws, objectMapper);

        gateway.executeAsync(1L);

        verify(taskMapper, never()).updateError(anyLong(), anyLong(), anyString());
        verify(taskMapper, never()).updateResult(anyLong(), anyLong(), anyString());
        verify(ws, never()).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    void executeAsync_taskNotFoundSkips() {
        when(taskMapper.findById(1L)).thenReturn(null);
        var gateway = new BacktestExecutionGateway(taskMapper, Optional.empty(), ws, objectMapper);

        gateway.executeAsync(1L);

        verify(taskMapper, never()).updateStatus(anyLong(), anyLong(), anyString(), anyString());
        verify(taskMapper, never()).updateError(anyLong(), anyLong(), anyString());
    }

    @Test
    void executeAsync_withRunner_marksCompleted() {
        when(taskMapper.findById(1L)).thenReturn(task(1L, 42L));
        when(taskMapper.updateStatus(1L, 42L, "PENDING", "RUNNING")).thenReturn(1);
        BacktestRunner runner = mock(BacktestRunner.class);
        when(runner.run(any())).thenReturn(new BacktestResult(BigDecimal.TEN, 7));
        var gateway = new BacktestExecutionGateway(taskMapper, Optional.of(runner), ws, objectMapper);

        gateway.executeAsync(1L);

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(taskMapper).updateResult(eq(1L), eq(42L), jsonCaptor.capture());
        String json = jsonCaptor.getValue();
        assertTrue(json.contains("realizedPnl"), "result JSON should contain realizedPnl: " + json);
        assertTrue(json.contains("10"), "result JSON should contain realizedPnl value 10: " + json);
        verify(ws)
                .convertAndSend(
                        eq("/topic/backtests/42"),
                        argThat((Object o) -> o instanceof Map<?, ?> m && "COMPLETED".equals(m.get("status"))));
        verify(taskMapper, never()).updateError(anyLong(), anyLong(), anyString());
    }

    @Test
    void executeAsync_runnerThrows_marksFailed() {
        when(taskMapper.findById(1L)).thenReturn(task(1L, 42L));
        when(taskMapper.updateStatus(1L, 42L, "PENDING", "RUNNING")).thenReturn(1);
        BacktestRunner runner = mock(BacktestRunner.class);
        when(runner.run(any())).thenThrow(new RuntimeException("worker crashed"));
        var gateway = new BacktestExecutionGateway(taskMapper, Optional.of(runner), ws, objectMapper);

        gateway.executeAsync(1L);

        verify(taskMapper).updateError(1L, 42L, "worker crashed");
        verify(taskMapper, never()).updateResult(anyLong(), anyLong(), anyString());
    }

    @Test
    void executeAsync_runnerThrowsWithoutMessage_usesClassSimpleName() {
        // 覆盖 BacktestExecutionGateway.markFailed 里 `e.getMessage() == null ? e.getClass().getSimpleName() : ...` 分支
        when(taskMapper.findById(1L)).thenReturn(task(1L, 42L));
        when(taskMapper.updateStatus(1L, 42L, "PENDING", "RUNNING")).thenReturn(1);
        BacktestRunner runner = mock(BacktestRunner.class);
        when(runner.run(any())).thenThrow(new NullPointerException()); // 无 message
        var gateway = new BacktestExecutionGateway(taskMapper, Optional.of(runner), ws, objectMapper);

        gateway.executeAsync(1L);

        verify(taskMapper).updateError(1L, 42L, "NullPointerException");
        verify(taskMapper, never()).updateResult(anyLong(), anyLong(), anyString());
    }

    private BacktestTask task(long id, long userId) {
        BacktestTask t =
                BacktestTask.create(1L, userId, 5L, "BTC/USDT", "BINANCE", "1h", Instant.now(), Instant.now(), "{}");
        t.setId(id);
        t.setStatus(BacktestTaskStatus.PENDING);
        return t;
    }
}
