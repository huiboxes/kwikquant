package com.kwikquant.strategy.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.kwikquant.shared.infra.ResourceStateConflictException;
import com.kwikquant.shared.types.StrategyId;
import com.kwikquant.shared.types.StrategyStatus;
import com.kwikquant.shared.types.StrategyStatusChangedEvent;
import com.kwikquant.strategy.domain.IllegalStrategyStateTransitionException;
import com.kwikquant.strategy.domain.NoPublishedStrategyCodeException;
import com.kwikquant.strategy.domain.StrategyCode;
import com.kwikquant.strategy.domain.StrategyDefinition;
import com.kwikquant.strategy.infrastructure.StrategyMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

class StrategyLifecycleServiceTest {

    private StrategyMapper strategyMapper;
    private StrategyCrudService crudService;
    private StrategyCodeService codeService;
    private WorkerOrchestratorService workerService;
    private ApplicationEventPublisher eventPublisher;
    private StrategyLifecycleService service;

    @BeforeEach
    void setUp() {
        strategyMapper = mock(StrategyMapper.class);
        crudService = mock(StrategyCrudService.class);
        codeService = mock(StrategyCodeService.class);
        workerService = mock(WorkerOrchestratorService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        service = new StrategyLifecycleService(strategyMapper, crudService, codeService, workerService, eventPublisher);
    }

    @Test
    void ready_draftToReady_noEvent() {
        StrategyDefinition s = strategy(1L, 42L, StrategyStatus.DRAFT);
        when(crudService.getOwned(1L, 42L)).thenReturn(s);
        when(strategyMapper.updateStatus(1L, 42L, "DRAFT", "READY")).thenReturn(1);

        StrategyDefinition result = service.ready(1L, 42L);

        assertEquals(StrategyStatus.READY, result.getStatus());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void ready_nonDraftThrows() {
        StrategyDefinition s = strategy(1L, 42L, StrategyStatus.RUNNING);
        when(crudService.getOwned(1L, 42L)).thenReturn(s);

        assertThrows(IllegalStrategyStateTransitionException.class, () -> service.ready(1L, 42L));
        verify(strategyMapper, never()).updateStatus(anyLong(), anyLong(), anyString(), anyString());
    }

    @Test
    void ready_casFailureThrowsConflict() {
        StrategyDefinition s = strategy(1L, 42L, StrategyStatus.DRAFT);
        when(crudService.getOwned(1L, 42L)).thenReturn(s);
        when(strategyMapper.updateStatus(1L, 42L, "DRAFT", "READY")).thenReturn(0);

        assertThrows(ResourceStateConflictException.class, () -> service.ready(1L, 42L));
    }

    @Test
    void start_readyWithPublishedCode_startsWorkerAndTransitions() {
        StrategyDefinition s = strategy(1L, 42L, StrategyStatus.READY);
        when(crudService.getOwned(1L, 42L)).thenReturn(s);
        when(codeService.getPublishedCode(1L)).thenReturn(code(5L, 1L));
        when(strategyMapper.updateStatus(1L, 42L, "READY", "RUNNING")).thenReturn(1);

        StrategyDefinition result = service.start(1L, 42L);

        verify(workerService).startWorker(any(StrategyDefinition.class), any(StrategyCode.class));
        assertEquals(StrategyStatus.RUNNING, result.getStatus());
        ArgumentCaptor<StrategyStatusChangedEvent> captor = ArgumentCaptor.forClass(StrategyStatusChangedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        StrategyStatusChangedEvent evt = captor.getValue();
        assertEquals(42L, evt.userId());
        assertEquals(new StrategyId(1L), evt.strategyId());
        assertEquals(StrategyStatus.READY, evt.previousStatus());
        assertEquals(StrategyStatus.RUNNING, evt.newStatus());
    }

    @Test
    void start_noPublishedCodeThrows() {
        StrategyDefinition s = strategy(1L, 42L, StrategyStatus.READY);
        when(crudService.getOwned(1L, 42L)).thenReturn(s);
        when(codeService.getPublishedCode(1L)).thenReturn(null);

        assertThrows(NoPublishedStrategyCodeException.class, () -> service.start(1L, 42L));
        verify(workerService, never()).startWorker(any(), any());
        verify(strategyMapper, never()).updateStatus(anyLong(), anyLong(), anyString(), anyString());
    }

    @Test
    void start_draftDirectlyThrows() {
        StrategyDefinition s = strategy(1L, 42L, StrategyStatus.DRAFT);
        when(crudService.getOwned(1L, 42L)).thenReturn(s);

        assertThrows(IllegalStrategyStateTransitionException.class, () -> service.start(1L, 42L));
    }

    @Test
    void start_casFailureStopsWorkerAndThrows() {
        StrategyDefinition s = strategy(1L, 42L, StrategyStatus.READY);
        when(crudService.getOwned(1L, 42L)).thenReturn(s);
        when(codeService.getPublishedCode(1L)).thenReturn(code(5L, 1L));
        when(strategyMapper.updateStatus(1L, 42L, "READY", "RUNNING")).thenReturn(0); // 并发竞争

        assertThrows(ResourceStateConflictException.class, () -> service.start(1L, 42L));
        verify(workerService).startWorker(any(), any()); // worker 已启动
        verify(workerService).stopWorker(1L); // CAS 失败后清理孤儿 worker
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void stop_runningToStopped() {
        StrategyDefinition s = strategy(1L, 42L, StrategyStatus.RUNNING);
        when(crudService.getOwned(1L, 42L)).thenReturn(s);
        when(strategyMapper.updateStatus(1L, 42L, "RUNNING", "STOPPED")).thenReturn(1);

        service.stop(1L, 42L);

        verify(workerService).stopWorker(1L);
        verify(eventPublisher).publishEvent(any(StrategyStatusChangedEvent.class));
    }

    @Test
    void stop_draftThrows() {
        StrategyDefinition s = strategy(1L, 42L, StrategyStatus.DRAFT);
        when(crudService.getOwned(1L, 42L)).thenReturn(s);

        assertThrows(IllegalStrategyStateTransitionException.class, () -> service.stop(1L, 42L));
        verify(workerService, never()).stopWorker(anyLong());
    }

    @Test
    void pause_runningToPaused() {
        StrategyDefinition s = strategy(1L, 42L, StrategyStatus.RUNNING);
        when(crudService.getOwned(1L, 42L)).thenReturn(s);
        when(strategyMapper.updateStatus(1L, 42L, "RUNNING", "PAUSED")).thenReturn(1);

        service.pause(1L, 42L);

        verify(workerService, never()).stopWorker(anyLong()); // pause 不停 Worker
        verify(eventPublisher).publishEvent(any(StrategyStatusChangedEvent.class));
    }

    @Test
    void pause_draftThrows() {
        StrategyDefinition s = strategy(1L, 42L, StrategyStatus.DRAFT);
        when(crudService.getOwned(1L, 42L)).thenReturn(s);

        assertThrows(IllegalStrategyStateTransitionException.class, () -> service.pause(1L, 42L));
    }

    @Test
    void markError_transitionsToErrorAndPublishes() {
        StrategyDefinition s = strategy(1L, 42L, StrategyStatus.RUNNING);
        when(strategyMapper.findById(1L)).thenReturn(s);
        when(strategyMapper.updateStatus(1L, 42L, "RUNNING", "ERROR")).thenReturn(1);

        service.markError(1L, "health fail");

        ArgumentCaptor<StrategyStatusChangedEvent> captor = ArgumentCaptor.forClass(StrategyStatusChangedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertEquals(StrategyStatus.ERROR, captor.getValue().newStatus());
        assertEquals(42L, captor.getValue().userId()); // 用策略 owner 的 userId
    }

    @Test
    void markError_casZeroIsIdempotent() {
        StrategyDefinition s = strategy(1L, 42L, StrategyStatus.ERROR); // 已是 ERROR
        when(strategyMapper.findById(1L)).thenReturn(s);
        when(strategyMapper.updateStatus(1L, 42L, "ERROR", "ERROR")).thenReturn(0);

        service.markError(1L, "again"); // 不抛
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void markError_strategyNotFound_isNoOp() {
        when(strategyMapper.findById(1L)).thenReturn(null);
        service.markError(1L, "x");
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void onWorkerMarkError_delegatesToMarkError() {
        StrategyDefinition s = strategy(1L, 42L, StrategyStatus.RUNNING);
        when(strategyMapper.findById(1L)).thenReturn(s);
        when(strategyMapper.updateStatus(1L, 42L, "RUNNING", "ERROR")).thenReturn(1);

        service.onWorkerMarkError(new WorkerMarkErrorEvent(1L, "health fail"));

        verify(eventPublisher).publishEvent(any(StrategyStatusChangedEvent.class));
    }

    private StrategyDefinition strategy(long id, long userId, StrategyStatus status) {
        StrategyDefinition s = StrategyDefinition.create(userId, "n", null, "BTC/USDT", "BINANCE", "SPOT", "1h", "{}");
        s.setId(id);
        s.setStatus(status);
        return s;
    }

    private StrategyCode code(long id, long strategyId) {
        StrategyCode c = StrategyCode.create(strategyId, 1, "def on_bar(): pass", "v1");
        c.setId(id);
        return c;
    }
}
