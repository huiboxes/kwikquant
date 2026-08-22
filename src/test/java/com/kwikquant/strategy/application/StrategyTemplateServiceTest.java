package com.kwikquant.strategy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kwikquant.strategy.domain.BacktestQuotaExceededException;
import com.kwikquant.strategy.domain.BacktestTask;
import com.kwikquant.strategy.domain.BacktestWorkerUnavailableException;
import com.kwikquant.strategy.domain.StrategyDefinition;
import com.kwikquant.strategy.domain.StrategyTemplate;
import com.kwikquant.strategy.domain.TemplateNotFoundException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class StrategyTemplateServiceTest {

    private static final StrategyTemplate TEMPLATE = new StrategyTemplate(
            "ma-double-cross",
            "均线双金叉",
            "desc",
            List.of("趋势跟踪"),
            "BTC/USDT",
            "BINANCE",
            "1h",
            "{}",
            90,
            "def on_bar(bar, ctx): pass");

    private StrategyTemplateCatalog catalog;
    private TemplateForkCreator forkCreator;
    private BacktestTaskService backtestTaskService;
    private StrategyTemplateService service;

    @BeforeEach
    void setUp() {
        catalog = mock(StrategyTemplateCatalog.class);
        forkCreator = mock(TemplateForkCreator.class);
        backtestTaskService = mock(BacktestTaskService.class);
        service = new StrategyTemplateService(catalog, forkCreator, backtestTaskService);
    }

    @Test
    void list_delegatesToCatalog() {
        when(catalog.all()).thenReturn(List.of(TEMPLATE));
        assertThat(service.list()).containsExactly(TEMPLATE);
    }

    @Test
    void require_knownKey_returnsTemplate() {
        when(catalog.get("ma-double-cross")).thenReturn(TEMPLATE);
        assertThat(service.require("ma-double-cross")).isSameAs(TEMPLATE);
    }

    @Test
    void require_unknownKey_throwsTemplateNotFound() {
        when(catalog.get("nope")).thenReturn(null);
        assertThatThrownBy(() -> service.require("nope"))
                .isInstanceOf(TemplateNotFoundException.class)
                .hasMessageContaining("nope");
    }

    @Test
    void fork_happyPath_createsStrategyAndSubmitsFirstBacktest() {
        when(catalog.get("ma-double-cross")).thenReturn(TEMPLATE);
        StrategyDefinition strategy = strategy(77L);
        when(forkCreator.createForked(42L, TEMPLATE)).thenReturn(strategy);
        BacktestTask task = task(501L);
        when(backtestTaskService.submit(eq(77L), eq(42L), isNull(), isNull(), isNull(), any(), any(), eq("{}")))
                .thenReturn(task);

        TemplateForkResult result = service.fork("ma-double-cross", 42L);

        assertThat(result.strategy()).isSameAs(strategy);
        assertThat(result.firstBacktestTaskId()).isEqualTo(501L);
        assertThat(result.backtestSkipReason()).isNull();
    }

    @Test
    void fork_submitWindow_matchesTemplateRecommendedDays_andAlignsToIntervalGrid() {
        when(catalog.get("ma-double-cross")).thenReturn(TEMPLATE);
        when(forkCreator.createForked(anyLong(), any())).thenReturn(strategy(77L));
        when(backtestTaskService.submit(anyLong(), anyLong(), any(), any(), any(), any(), any(), any()))
                .thenReturn(task(501L));

        service.fork("ma-double-cross", 42L);

        ArgumentCaptor<Instant> startCap = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> endCap = ArgumentCaptor.forClass(Instant.class);
        verify(backtestTaskService)
                .submit(eq(77L), eq(42L), isNull(), isNull(), isNull(), startCap.capture(), endCap.capture(), eq("{}"));
        Instant start = startCap.getValue();
        Instant end = endCap.getValue();
        // 窗口 = 模板推荐天数;symbol/exchange/interval 传 null 回落策略默认(=模板声明值)
        assertThat(Duration.between(start, end)).isEqualTo(Duration.ofDays(90));
        // endTime 对齐 interval 网格(1h = 3600s 整除)
        assertThat(end.toEpochMilli() % 3_600_000L).isZero();
    }

    @Test
    void fork_quotaExceeded_forkSucceedsWithSkipReason() {
        when(catalog.get("ma-double-cross")).thenReturn(TEMPLATE);
        StrategyDefinition strategy = strategy(77L);
        when(forkCreator.createForked(42L, TEMPLATE)).thenReturn(strategy);
        when(backtestTaskService.submit(anyLong(), anyLong(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new BacktestQuotaExceededException(2, 2));

        TemplateForkResult result = service.fork("ma-double-cross", 42L);

        assertThat(result.strategy()).isSameAs(strategy);
        assertThat(result.firstBacktestTaskId()).isNull();
        assertThat(result.backtestSkipReason()).contains("配额");
    }

    @Test
    void fork_workerUnavailable_forkSucceedsWithSkipReason() {
        when(catalog.get("ma-double-cross")).thenReturn(TEMPLATE);
        when(forkCreator.createForked(42L, TEMPLATE)).thenReturn(strategy(77L));
        when(backtestTaskService.submit(anyLong(), anyLong(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new BacktestWorkerUnavailableException("python 不可用"));

        TemplateForkResult result = service.fork("ma-double-cross", 42L);

        assertThat(result.firstBacktestTaskId()).isNull();
        assertThat(result.backtestSkipReason()).contains("不可用");
    }

    @Test
    void fork_workerAutoSetupInProgress_skipReasonSaysPreparing() {
        // 环境自动搭建窗口与真实故障分开表述，前者不该让用户以为平台坏了
        when(catalog.get("ma-double-cross")).thenReturn(TEMPLATE);
        when(forkCreator.createForked(42L, TEMPLATE)).thenReturn(strategy(77L));
        when(backtestTaskService.submit(anyLong(), anyLong(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new BacktestWorkerUnavailableException("回测运行环境正在自动准备（创建虚拟环境并安装依赖，首次约 1-3 分钟），请稍候重试"));

        TemplateForkResult result = service.fork("ma-double-cross", 42L);

        assertThat(result.firstBacktestTaskId()).isNull();
        assertThat(result.backtestSkipReason()).contains("正在自动准备");
        assertThat(result.backtestSkipReason()).doesNotContain("不可用");
    }

    @Test
    void fork_workerSelfCheckInProgress_skipReasonAlsoTransitional() {
        // 启动后自检窗口(秒级)与搭建窗口同属过渡态,口径一致
        when(catalog.get("ma-double-cross")).thenReturn(TEMPLATE);
        when(forkCreator.createForked(42L, TEMPLATE)).thenReturn(strategy(77L));
        when(backtestTaskService.submit(anyLong(), anyLong(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new BacktestWorkerUnavailableException("自检进行中，请稍候重试"));

        TemplateForkResult result = service.fork("ma-double-cross", 42L);

        assertThat(result.backtestSkipReason()).contains("正在自动准备");
    }

    @Test
    void fork_unexpectedSubmitFailure_forkSucceedsWithGenericSkipReason() {
        when(catalog.get("ma-double-cross")).thenReturn(TEMPLATE);
        when(forkCreator.createForked(42L, TEMPLATE)).thenReturn(strategy(77L));
        when(backtestTaskService.submit(anyLong(), anyLong(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("boom"));

        TemplateForkResult result = service.fork("ma-double-cross", 42L);

        assertThat(result.firstBacktestTaskId()).isNull();
        assertThat(result.backtestSkipReason()).contains("手动");
    }

    @Test
    void fork_unknownKey_doesNotCreateStrategy() {
        when(catalog.get("nope")).thenReturn(null);
        assertThatThrownBy(() -> service.fork("nope", 42L)).isInstanceOf(TemplateNotFoundException.class);
        verify(forkCreator, org.mockito.Mockito.never()).createForked(anyLong(), any());
    }

    private static StrategyDefinition strategy(long id) {
        StrategyDefinition s =
                StrategyDefinition.create(42L, "均线双金叉", "desc", "BTC/USDT", "BINANCE", "SPOT", "1h", "{}");
        s.setId(id);
        return s;
    }

    private static BacktestTask task(long id) {
        BacktestTask t = BacktestTask.create(
                77L, 42L, 1L, "BTC/USDT", "BINANCE", "SPOT", "1h", Instant.now(), Instant.now(), "{}");
        t.setId(id);
        return t;
    }
}
