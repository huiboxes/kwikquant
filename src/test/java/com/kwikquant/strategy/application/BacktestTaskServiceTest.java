package com.kwikquant.strategy.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.kwikquant.shared.infra.OwnershipViolationException;
import com.kwikquant.shared.types.StrategyStatus;
import com.kwikquant.strategy.domain.BacktestTask;
import com.kwikquant.strategy.domain.BacktestTaskNotFoundException;
import com.kwikquant.strategy.domain.BacktestTaskStatus;
import com.kwikquant.strategy.domain.NoPublishedStrategyCodeException;
import com.kwikquant.strategy.domain.StrategyCode;
import com.kwikquant.strategy.domain.StrategyDefinition;
import com.kwikquant.strategy.infrastructure.BacktestTaskMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BacktestTaskServiceTest {

    private BacktestTaskMapper taskMapper;
    private StrategyCrudService crudService;
    private StrategyCodeService codeService;
    private BacktestExecutionGateway gateway;
    private BacktestTaskService service;

    @BeforeEach
    void setUp() {
        taskMapper = mock(BacktestTaskMapper.class);
        crudService = mock(StrategyCrudService.class);
        codeService = mock(StrategyCodeService.class);
        gateway = mock(BacktestExecutionGateway.class);
        // 模拟 MyBatis @Options(useGeneratedKeys) 回填 id
        doAnswer(inv -> {
                    ((BacktestTask) inv.getArgument(0)).setId(1L);
                    return null;
                })
                .doNothing()
                .when(taskMapper)
                .insert(any(BacktestTask.class));
        service = new BacktestTaskService(taskMapper, crudService, codeService, gateway);
    }

    @Test
    void submit_noPublishedCodeThrows() {
        when(crudService.getOwned(1L, 42L)).thenReturn(strategy(1L, 42L));
        when(codeService.getPublishedCode(1L)).thenReturn(null);

        assertThrows(
                NoPublishedStrategyCodeException.class,
                () -> service.submit(1L, 42L, null, null, null, Instant.now(), Instant.now(), "{}"));
        verify(taskMapper, never()).insert(any());
        verify(gateway, never()).executeAsync(anyLong());
    }

    @Test
    void submit_createsPendingAndTriggersGateway() {
        when(crudService.getOwned(1L, 42L)).thenReturn(strategy(1L, 42L));
        when(codeService.getPublishedCode(1L)).thenReturn(publishedCode(5L, 1L));

        BacktestTask task = service.submit(
                1L,
                42L,
                "BTC/USDT",
                "BINANCE",
                "1h",
                Instant.parse("2025-01-01T00:00:00Z"),
                Instant.parse("2025-06-01T00:00:00Z"),
                "{\"fast\":14}");

        assertEquals(BacktestTaskStatus.PENDING, task.getStatus());
        assertEquals(5L, task.getStrategyCodeId());
        assertEquals("BTC/USDT", task.getSymbol());
        verify(taskMapper).insert(any(BacktestTask.class));
        verify(gateway).executeAsync(anyLong());
    }

    @Test
    void submit_usesStrategyDefaultsWhenOverridesNull() {
        StrategyDefinition s = strategy(1L, 42L); // symbol BTC/USDT, BINANCE, 1h
        when(crudService.getOwned(1L, 42L)).thenReturn(s);
        when(codeService.getPublishedCode(1L)).thenReturn(publishedCode(5L, 1L));

        BacktestTask task = service.submit(
                1L,
                42L,
                null,
                null,
                null,
                Instant.parse("2025-01-01T00:00:00Z"),
                Instant.parse("2025-06-01T00:00:00Z"),
                null);

        assertEquals("BTC/USDT", task.getSymbol());
        assertEquals("BINANCE", task.getExchange());
        assertEquals("1h", task.getIntervalValue());
        assertEquals("{}", task.getParameters());
    }

    @Test
    void submit_paperExchange_throws() {
        // 回测 exchange 不能是 PAPER(模拟盘 exchange='OKX' 非 PAPER,真实交易所)
        when(crudService.getOwned(1L, 42L)).thenReturn(strategy(1L, 42L));
        when(codeService.getPublishedCode(1L)).thenReturn(publishedCode(5L, 1L));

        assertThrows(
                IllegalArgumentException.class,
                () -> service.submit(
                        1L,
                        42L,
                        "BTC/USDT",
                        "PAPER",
                        "1h",
                        Instant.parse("2025-01-01T00:00:00Z"),
                        Instant.parse("2025-06-01T00:00:00Z"),
                        "{}"));
        verify(taskMapper, never()).insert(any());
        verify(gateway, never()).executeAsync(anyLong());
    }

    @Test
    void submit_invalidExchange_throws() {
        when(crudService.getOwned(1L, 42L)).thenReturn(strategy(1L, 42L));
        when(codeService.getPublishedCode(1L)).thenReturn(publishedCode(5L, 1L));

        assertThrows(
                IllegalArgumentException.class,
                () -> service.submit(
                        1L,
                        42L,
                        "BTC/USDT",
                        "NOT_AN_EXCHANGE",
                        "1h",
                        Instant.parse("2025-01-01T00:00:00Z"),
                        Instant.parse("2025-06-01T00:00:00Z"),
                        "{}"));
        verify(taskMapper, never()).insert(any());
    }

    @Test
    void submit_invalidDateRange_throws() {
        // startTime >= endTime → 抛(start==end 也非法,0 根 K 线无意义)
        when(crudService.getOwned(1L, 42L)).thenReturn(strategy(1L, 42L));
        when(codeService.getPublishedCode(1L)).thenReturn(publishedCode(5L, 1L));

        Instant t = Instant.parse("2025-01-01T00:00:00Z");
        assertThrows(
                IllegalArgumentException.class, () -> service.submit(1L, 42L, "BTC/USDT", "BINANCE", "1h", t, t, "{}"));
        verify(taskMapper, never()).insert(any());
    }

    @Test
    void getOwned_returnsWhenOwner() {
        BacktestTask t = task(1L, 42L);
        when(taskMapper.findById(1L)).thenReturn(t);
        assertEquals(1L, service.getOwned(1L, 42L).getId());
    }

    @Test
    void getOwned_throws404WhenNotFound() {
        when(taskMapper.findById(1L)).thenReturn(null);
        assertThrows(BacktestTaskNotFoundException.class, () -> service.getOwned(1L, 42L));
    }

    @Test
    void getOwned_throws403WhenNotOwner() {
        BacktestTask t = task(1L, 99L);
        when(taskMapper.findById(1L)).thenReturn(t);
        assertThrows(OwnershipViolationException.class, () -> service.getOwned(1L, 42L));
    }

    @Test
    void listByStrategy_delegatesOwnershipThenQueries() {
        when(crudService.getOwned(1L, 42L)).thenReturn(strategy(1L, 42L));
        when(taskMapper.findByStrategyId(1L)).thenReturn(List.of(task(1L, 42L)));

        List<BacktestTask> tasks = service.listByStrategy(1L, 42L);
        assertEquals(1, tasks.size());
    }

    private StrategyDefinition strategy(long id, long userId) {
        StrategyDefinition s = StrategyDefinition.create(userId, "n", null, "BTC/USDT", "BINANCE", "SPOT", "1h", "{}");
        s.setId(id);
        s.setStatus(StrategyStatus.READY);
        return s;
    }

    private StrategyCode publishedCode(long id, long strategyId) {
        StrategyCode c = StrategyCode.create(strategyId, 1, "def on_bar(): pass", "v1");
        c.setId(id);
        return c;
    }

    private BacktestTask task(long id, long userId) {
        BacktestTask t =
                BacktestTask.create(1L, userId, 5L, "BTC/USDT", "BINANCE", "1h", Instant.now(), Instant.now(), "{}");
        t.setId(id);
        return t;
    }
}
