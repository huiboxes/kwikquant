package com.kwikquant.strategy.interfaces;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kwikquant.shared.infra.ApiResponse;
import com.kwikquant.strategy.application.BacktestTaskService;
import com.kwikquant.strategy.domain.BacktestTask;
import com.kwikquant.strategy.domain.BacktestTaskStatus;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Unit tests for {@link BacktestController}.
 *
 * <p>Pure Mockito style(consistent with AuthControllerTest)。覆盖 submit/get/list 三个端点,
 * 重点守 {@link BacktestController.BacktestTaskDto#from} 从实体回填 processedBars/totalBars
 * (契约缺口补后新字段):RUNNING 时透传进度数据,PENDING 时 null。
 */
class BacktestControllerTest {

    private BacktestTaskService taskService;
    private BacktestController controller;

    @BeforeEach
    void setUp() {
        taskService = mock(BacktestTaskService.class);
        controller = new BacktestController(taskService);
        // SecurityUtils.currentUserId() = Long.parseLong(principal);"42" → 42L
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("42", "x"));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /** 构造 RUNNING task(create 返 PENDING → transitionTo RUNNING → 上报进度)。 */
    private static BacktestTask runningTask(int processed, int total) {
        BacktestTask t = BacktestTask.create(
                128L,
                42L,
                256L,
                "BTC/USDT",
                "OKX",
                "SPOT",
                "1h",
                Instant.parse("2026-06-01T00:00:00Z"),
                Instant.parse("2026-07-01T00:00:00Z"),
                "{}");
        t.setId(1L);
        t.transitionTo(BacktestTaskStatus.RUNNING);
        t.setProcessedBars(processed);
        t.setTotalBars(total);
        t.setCreatedAt(Instant.parse("2026-07-04T12:00:00Z"));
        t.setUpdatedAt(Instant.parse("2026-07-04T12:00:05Z"));
        return t;
    }

    @Test
    void list_runningTask_mapsProcessedBarsAndTotalBarsToDto() {
        when(taskService.listByStrategy(128L, 42L)).thenReturn(List.of(runningTask(4400, 8760)));

        ApiResponse<List<BacktestController.BacktestTaskDto>> result = controller.list(128L);

        assertThat(result.data()).hasSize(1);
        var dto = result.data().get(0);
        assertThat(dto.status()).isEqualTo(BacktestTaskStatus.RUNNING);
        assertThat(dto.processedBars()).isEqualTo(4400);
        assertThat(dto.totalBars()).isEqualTo(8760);
    }

    @Test
    void list_withoutStrategyId_callsListByUser() {
        // strategyId 不传(nullable) → 走全列表路径调 listByUser
        when(taskService.listByUser(42L)).thenReturn(List.of());

        ApiResponse<List<BacktestController.BacktestTaskDto>> result = controller.list(null);

        verify(taskService).listByUser(42L);
        assertThat(result.data()).isEmpty();
    }

    @Test
    void list_withStrategyId_callsListByStrategy() {
        // strategyId 传 → 走既有按策略路径(不回归)
        when(taskService.listByStrategy(128L, 42L)).thenReturn(List.of());

        controller.list(128L);

        verify(taskService).listByStrategy(128L, 42L);
    }

    @Test
    void get_runningTask_mapsProcessedBarsAndTotalBarsToDto() {
        when(taskService.getOwned(1L, 42L)).thenReturn(runningTask(100, 200));

        ApiResponse<BacktestController.BacktestTaskDto> result = controller.get(1L);

        assertThat(result.data().processedBars()).isEqualTo(100);
        assertThat(result.data().totalBars()).isEqualTo(200);
    }

    @Test
    void submit_pendingTask_mapsNullProcessedBarsAndTotalBarsToDto() {
        // create 返 PENDING task(processedBars/totalBars 未设 = null)
        BacktestTask pending = BacktestTask.create(
                128L,
                42L,
                256L,
                "BTC/USDT",
                "OKX",
                "SPOT",
                "1h",
                Instant.parse("2026-06-01T00:00:00Z"),
                Instant.parse("2026-07-01T00:00:00Z"),
                "{}");
        pending.setId(2L);
        when(taskService.submit(eq(128L), eq(42L), eq("BTC/USDT"), eq("OKX"), eq("1h"), any(), any(), eq("{}")))
                .thenReturn(pending);

        var req = new BacktestController.SubmitBacktestRequest(
                128L,
                "BTC/USDT",
                "OKX",
                "1h",
                Instant.parse("2026-06-01T00:00:00Z"),
                Instant.parse("2026-07-01T00:00:00Z"),
                "{}");
        ApiResponse<BacktestController.BacktestTaskDto> result = controller.submit(req);

        assertThat(result.data().status()).isEqualTo(BacktestTaskStatus.PENDING);
        assertThat(result.data().processedBars()).isNull();
        assertThat(result.data().totalBars()).isNull();
    }
}
