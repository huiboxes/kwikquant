package com.kwikquant.strategy.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.kwikquant.account.application.ExchangeAccountService;
import com.kwikquant.account.domain.ExchangeAccount;
import com.kwikquant.shared.infra.ResourceStateConflictException;
import com.kwikquant.shared.types.Exchange;
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
    private ExchangeAccountService accountService;
    private StrategyLifecycleService service;

    @BeforeEach
    void setUp() {
        strategyMapper = mock(StrategyMapper.class);
        crudService = mock(StrategyCrudService.class);
        codeService = mock(StrategyCodeService.class);
        workerService = mock(WorkerOrchestratorService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        accountService = mock(ExchangeAccountService.class);
        service = new StrategyLifecycleService(
                strategyMapper, crudService, codeService, workerService, eventPublisher, accountService);
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
        when(accountService.getOwned(7L, 42L)).thenReturn(account(Exchange.BINANCE));
        when(strategyMapper.updateStatusWithReason(1L, 42L, "READY", "RUNNING", null))
                .thenReturn(1);

        StrategyDefinition result = service.start(1L, 42L, 7L);

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

        when(accountService.getOwned(7L, 42L)).thenReturn(account(Exchange.BINANCE));
        assertThrows(NoPublishedStrategyCodeException.class, () -> service.start(1L, 42L, 7L));
        verify(workerService, never()).startWorker(any(), any());
        verify(strategyMapper, never()).updateStatus(anyLong(), anyLong(), anyString(), anyString());
    }

    @Test
    void start_draftDirectlyThrows() {
        StrategyDefinition s = strategy(1L, 42L, StrategyStatus.DRAFT);
        when(crudService.getOwned(1L, 42L)).thenReturn(s);

        assertThrows(IllegalStrategyStateTransitionException.class, () -> service.start(1L, 42L, 7L));
    }

    @Test
    void start_casFailureStopsWorkerAndThrows() {
        StrategyDefinition s = strategy(1L, 42L, StrategyStatus.READY);
        when(crudService.getOwned(1L, 42L)).thenReturn(s);
        when(codeService.getPublishedCode(1L)).thenReturn(code(5L, 1L));
        when(accountService.getOwned(7L, 42L)).thenReturn(account(Exchange.BINANCE));
        when(strategyMapper.updateStatusWithReason(1L, 42L, "READY", "RUNNING", null))
                .thenReturn(0); // 并发竞争

        assertThrows(ResourceStateConflictException.class, () -> service.start(1L, 42L, 7L));
        verify(workerService).startWorker(any(), any()); // worker 已启动
        verify(workerService).stopWorker(1L); // CAS 失败后清理孤儿 worker
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void start_resumeWithNullAccountId_usesBoundAccount() {
        // resume(PAUSED→RUNNING):accountId null → 用已绑 exchange_account_id,不验 account
        StrategyDefinition s = strategy(1L, 42L, StrategyStatus.PAUSED);
        s.setExchangeAccountId(7L);
        when(crudService.getOwned(1L, 42L)).thenReturn(s);
        when(codeService.getPublishedCode(1L)).thenReturn(code(5L, 1L));
        when(strategyMapper.updateStatusWithReason(1L, 42L, "PAUSED", "RUNNING", null))
                .thenReturn(1);

        StrategyDefinition result = service.start(1L, 42L, null);

        verify(workerService).startWorker(any(StrategyDefinition.class), any(StrategyCode.class));
        verify(accountService, never()).getOwned(anyLong(), anyLong());
        verify(strategyMapper, never()).updateExchangeAccountId(anyLong(), anyLong(), any());
        assertEquals(StrategyStatus.RUNNING, result.getStatus());
    }

    @Test
    void start_resumeWithNullAccountId_noBoundAccount_throws() {
        // resume 但 strategy 未绑账户(exchange_account_id=null)→ 抛(需先选账户启动)
        StrategyDefinition s = strategy(1L, 42L, StrategyStatus.PAUSED);
        when(crudService.getOwned(1L, 42L)).thenReturn(s);

        assertThrows(IllegalArgumentException.class, () -> service.start(1L, 42L, null));
        verify(workerService, never()).startWorker(any(), any());
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
        when(strategyMapper.updateStatusWithReason(1L, 42L, "RUNNING", "ERROR", "health fail"))
                .thenReturn(1);

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
        when(strategyMapper.updateStatusWithReason(1L, 42L, "ERROR", "ERROR", "again"))
                .thenReturn(0);

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
    void restart_stoppedToRunning_startsWorkerAndTransitions() {
        StrategyDefinition s = strategy(1L, 42L, StrategyStatus.STOPPED);
        s.setExchangeAccountId(7L);
        when(crudService.getOwned(1L, 42L)).thenReturn(s);
        when(codeService.getPublishedCode(1L)).thenReturn(code(5L, 1L));
        when(strategyMapper.updateStatusWithReason(1L, 42L, "STOPPED", "RUNNING", null))
                .thenReturn(1);

        StrategyDefinition result = service.restart(1L, 42L, null);

        verify(workerService).startWorker(any(StrategyDefinition.class), any(StrategyCode.class));
        assertEquals(StrategyStatus.RUNNING, result.getStatus());
        ArgumentCaptor<StrategyStatusChangedEvent> captor = ArgumentCaptor.forClass(StrategyStatusChangedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertEquals(StrategyStatus.STOPPED, captor.getValue().previousStatus());
        assertEquals(StrategyStatus.RUNNING, captor.getValue().newStatus());
    }

    @Test
    void restart_nonStoppedThrows() {
        for (StrategyStatus src : new StrategyStatus[] {
            StrategyStatus.DRAFT,
            StrategyStatus.READY,
            StrategyStatus.RUNNING,
            StrategyStatus.PAUSED,
            StrategyStatus.ERROR
        }) {
            StrategyDefinition s = strategy(1L, 42L, src);
            when(crudService.getOwned(1L, 42L)).thenReturn(s);
            assertThrows(
                    IllegalStrategyStateTransitionException.class,
                    () -> service.restart(1L, 42L, 7L),
                    "from " + src + " should throw");
        }
        verify(workerService, never()).startWorker(any(), any());
        verify(strategyMapper, never()).updateStatus(anyLong(), anyLong(), anyString(), anyString());
    }

    @Test
    void restart_noPublishedCodeThrows() {
        StrategyDefinition s = strategy(1L, 42L, StrategyStatus.STOPPED);
        s.setExchangeAccountId(7L); // null accountId 分支需已绑账户,才能走到 getPublishedCode 检查
        when(crudService.getOwned(1L, 42L)).thenReturn(s);
        when(codeService.getPublishedCode(1L)).thenReturn(null);

        assertThrows(NoPublishedStrategyCodeException.class, () -> service.restart(1L, 42L, null));
        verify(workerService, never()).startWorker(any(), any());
        verify(strategyMapper, never()).updateStatus(anyLong(), anyLong(), anyString(), anyString());
    }

    @Test
    void restart_casFailureStopsWorkerAndThrows() {
        StrategyDefinition s = strategy(1L, 42L, StrategyStatus.STOPPED);
        s.setExchangeAccountId(7L);
        when(crudService.getOwned(1L, 42L)).thenReturn(s);
        when(codeService.getPublishedCode(1L)).thenReturn(code(5L, 1L));
        when(strategyMapper.updateStatusWithReason(1L, 42L, "STOPPED", "RUNNING", null))
                .thenReturn(0); // 并发竞争

        assertThrows(ResourceStateConflictException.class, () -> service.restart(1L, 42L, null));
        verify(workerService).startWorker(any(), any()); // worker 已启动
        verify(workerService).stopWorker(1L); // CAS 失败清理孤儿
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void restart_switchAccount_bindsNewAccount() {
        StrategyDefinition s = strategy(1L, 42L, StrategyStatus.STOPPED);
        s.setExchangeAccountId(7L); // 原绑账户
        when(crudService.getOwned(1L, 42L)).thenReturn(s);
        when(codeService.getPublishedCode(1L)).thenReturn(code(5L, 1L));
        when(accountService.getOwned(9L, 42L)).thenReturn(account(Exchange.BINANCE)); // 新账户同 exchange
        when(strategyMapper.updateStatusWithReason(1L, 42L, "STOPPED", "RUNNING", null))
                .thenReturn(1);

        service.restart(1L, 42L, 9L);

        verify(strategyMapper).updateExchangeAccountId(1L, 42L, 9L);
        assertEquals(9L, s.getExchangeAccountId());
    }

    @Test
    void restart_exchangeMismatchThrows() {
        StrategyDefinition s = strategy(1L, 42L, StrategyStatus.STOPPED);
        when(crudService.getOwned(1L, 42L)).thenReturn(s);
        // strategy.exchange = BINANCE（helper strategy() 写死 BINANCE），账户 exchange = OKX
        when(accountService.getOwned(9L, 42L)).thenReturn(account(Exchange.OKX));

        assertThrows(IllegalArgumentException.class, () -> service.restart(1L, 42L, 9L));
        verify(workerService, never()).startWorker(any(), any());
    }

    @Test
    void restart_nullAccountIdNoBoundAccountThrows() {
        StrategyDefinition s = strategy(1L, 42L, StrategyStatus.STOPPED);
        // exchangeAccountId 未设（null）
        when(crudService.getOwned(1L, 42L)).thenReturn(s);

        assertThrows(IllegalArgumentException.class, () -> service.restart(1L, 42L, null));
        verify(workerService, never()).startWorker(any(), any());
    }

    @Test
    void onWorkerMarkError_delegatesToMarkError() {
        StrategyDefinition s = strategy(1L, 42L, StrategyStatus.RUNNING);
        when(strategyMapper.findById(1L)).thenReturn(s);
        when(strategyMapper.updateStatusWithReason(1L, 42L, "RUNNING", "ERROR", "health fail"))
                .thenReturn(1);

        service.onWorkerMarkError(new WorkerMarkErrorEvent(1L, "health fail"));

        verify(eventPublisher).publishEvent(any(StrategyStatusChangedEvent.class));
    }

    private StrategyDefinition strategy(long id, long userId, StrategyStatus status) {
        StrategyDefinition s = StrategyDefinition.create(userId, "n", null, "BTC/USDT", "BINANCE", "SPOT", "1h", "{}");
        s.setId(id);
        s.setStatus(status);
        return s;
    }

    private ExchangeAccount account(Exchange exchange) {
        // 真实 ExchangeAccount(非 mock):避免在 when(accountService.getOwned).thenReturn(account()) 内
        // 嵌套 mock()+when() 导致 Mockito UnfinishedStubbing
        ExchangeAccount a = new ExchangeAccount();
        a.setUserId(42L);
        a.setExchange(exchange);
        return a;
    }

    private StrategyCode code(long id, long strategyId) {
        StrategyCode c = StrategyCode.create(strategyId, 1, "def on_bar(): pass", "v1");
        c.setId(id);
        return c;
    }
}
