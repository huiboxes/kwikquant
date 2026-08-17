package com.kwikquant.strategy.interfaces;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.kwikquant.market.application.MarketDataService;
import com.kwikquant.market.domain.Kline;
import com.kwikquant.shared.infra.ResourceStateConflictException;
import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.Interval;
import com.kwikquant.shared.types.MarketType;
import com.kwikquant.strategy.application.BacktestTaskService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;

/**
 * BacktestWorkerController 单测:Worker 通道两端点(klines 数据 + progress 心跳)委托验证。
 * (X-Worker-Token 鉴权由 WorkerTokenFilter 拦,见 WorkerTokenFilterTest;撮合本地化后
 * 回测 Worker 与 app 的 HTTP 交互仅剩这两个端点。)
 *
 * <p>klines 先过任务快照校验({@code requireKlineRequestWithinTask}):参数/区间必须与任务冻结
 * 快照一致,否则不触达行情服务(防 token 当通配行情代理)。
 */
class BacktestWorkerControllerTest {

    private final BacktestTaskService taskService = mock(BacktestTaskService.class);
    private final MarketDataService marketDataService = mock(MarketDataService.class);
    private final BacktestWorkerController controller = new BacktestWorkerController(taskService, marketDataService);

    @Test
    void klines_delegatesToDbFirstFetch_afterSnapshotGuard() {
        Instant start = Instant.parse("2024-01-01T00:00:00Z");
        Instant end = Instant.parse("2024-01-02T00:00:00Z");
        Kline k = new Kline(
                Exchange.OKX,
                MarketType.SPOT,
                "BTC/USDT",
                Interval._1h,
                start,
                BigDecimal.ONE,
                BigDecimal.TEN,
                BigDecimal.ONE,
                BigDecimal.TEN,
                BigDecimal.ONE);
        when(marketDataService.fetchKlineRangeDbFirst(
                        Exchange.OKX, MarketType.SPOT, "BTC/USDT", Interval._1h, start, end))
                .thenReturn(List.of(k));

        var resp = controller.klines(42L, Exchange.OKX, MarketType.SPOT, "BTC/USDT", Interval._1h, start, end);

        assertThat(resp.code()).isEqualTo(0);
        assertThat(resp.data()).containsExactly(k);
        // 校验在取数之前(顺序 + 入参原样透传)
        InOrder inOrder = inOrder(taskService, marketDataService);
        inOrder.verify(taskService)
                .requireKlineRequestWithinTask(
                        42L, Exchange.OKX, MarketType.SPOT, "BTC/USDT", Interval._1h, start, end);
        inOrder.verify(marketDataService)
                .fetchKlineRangeDbFirst(Exchange.OKX, MarketType.SPOT, "BTC/USDT", Interval._1h, start, end);
    }

    @Test
    void klines_snapshotGuardRejects_noMarketDataFetch() {
        // 任务非 RUNNING → 409/4009,且不触达行情服务(guard 抛在 fetch 之前)
        Instant start = Instant.parse("2024-01-01T00:00:00Z");
        Instant end = Instant.parse("2024-01-02T00:00:00Z");
        doThrow(new ResourceStateConflictException("backtest_task 42 is COMPLETED"))
                .when(taskService)
                .requireKlineRequestWithinTask(
                        eq(42L),
                        eq(Exchange.OKX),
                        eq(MarketType.SPOT),
                        eq("BTC/USDT"),
                        eq(Interval._1h),
                        eq(start),
                        eq(end));

        assertThatThrownBy(() ->
                        controller.klines(42L, Exchange.OKX, MarketType.SPOT, "BTC/USDT", Interval._1h, start, end))
                .isInstanceOf(ResourceStateConflictException.class);
        verifyNoInteractions(marketDataService);
    }

    @Test
    void klines_snapshotGuardParamMismatch_noMarketDataFetch() {
        // symbol 与任务快照不符 → 400/3001(guard IllegalArgumentException),不触达行情服务
        Instant start = Instant.parse("2024-01-01T00:00:00Z");
        Instant end = Instant.parse("2024-01-02T00:00:00Z");
        doThrow(new IllegalArgumentException("klines symbol mismatch"))
                .when(taskService)
                .requireKlineRequestWithinTask(anyLong(), any(), any(), anyString(), any(), any(), any());

        assertThatThrownBy(() ->
                        controller.klines(42L, Exchange.OKX, MarketType.SPOT, "ETH/USDT", Interval._1h, start, end))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(marketDataService);
    }

    @Test
    void reportProgress_delegatesToServiceAndReturns204() {
        var resp = controller.reportProgress(42L, new BacktestWorkerController.BacktestProgressRequest(4400, 8760));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(resp.hasBody()).isFalse();
        verify(taskService).reportProgress(eq(42L), eq(4400), eq(8760));
        verifyNoMoreInteractions(taskService);
    }
}
