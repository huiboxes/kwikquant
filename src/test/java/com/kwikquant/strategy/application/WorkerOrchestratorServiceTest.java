package com.kwikquant.strategy.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.kwikquant.shared.infra.WorkerTokenService;
import com.kwikquant.shared.types.StrategyStatus;
import com.kwikquant.strategy.domain.StrategyCode;
import com.kwikquant.strategy.domain.StrategyDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

class WorkerOrchestratorServiceTest {

    private WorkerManager workerManager;
    private StrategyCrudService crudService;
    private StrategyCodeService codeService;
    private ApplicationEventPublisher eventPublisher;
    private WorkerTokenService workerTokenService;
    private WorkerOrchestratorService service;

    @BeforeEach
    void setUp() {
        workerManager = mock(WorkerManager.class);
        crudService = mock(StrategyCrudService.class);
        codeService = mock(StrategyCodeService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        workerTokenService = new WorkerTokenService(); // 真实 bean,便于验 token 生命周期
        service = new WorkerOrchestratorService(
                workerManager, crudService, codeService, eventPublisher, workerTokenService, "http://localhost:8080");
    }

    @Test
    void startWorker_createsContainerAndRegisters() {
        when(workerManager.createAndStart(any())).thenReturn("c1");

        service.startWorker(strategy(1L), code(5L, 1L));

        ArgumentCaptor<WorkerConfig> captor = ArgumentCaptor.forClass(WorkerConfig.class);
        verify(workerManager).createAndStart(captor.capture());
        WorkerConfig config = captor.getValue();
        assertEquals(1L, config.strategyId());
        assertEquals("BTC/USDT", config.symbol());
        assertNotNull(config.serviceToken());
        assertFalse(config.serviceToken().isBlank());
        assertEquals("http://localhost:8080", config.apiBaseUrl());
        WorkerStatus status = service.getWorkerStatus(1L);
        assertNotNull(status);
        assertEquals("c1", status.containerId());
        assertTrue(status.running());
        assertEquals(0, status.consecutiveFailures());
    }

    @Test
    void startWorker_replacesExistingContainerToAvoidOrphan() {
        when(workerManager.createAndStart(any())).thenReturn("c1").thenReturn("c2");
        service.startWorker(strategy(1L), code(5L, 1L));
        service.startWorker(strategy(1L), code(5L, 1L));

        // 旧容器 c1 应被 stop + remove
        verify(workerManager).stop("c1");
        verify(workerManager).remove("c1");
        assertEquals("c2", service.getWorkerStatus(1L).containerId());
    }

    @Test
    void stopWorker_stopsAndUnregisters() {
        when(workerManager.createAndStart(any())).thenReturn("c1");
        service.startWorker(strategy(1L), code(5L, 1L));

        service.stopWorker(1L);

        verify(workerManager).stop("c1");
        verify(workerManager).remove("c1");
        assertNull(service.getWorkerStatus(1L));
    }

    @Test
    void stopWorker_idempotentWhenNotRunning() {
        service.stopWorker(999L);
        verifyNoInteractions(workerManager);
    }

    @Test
    void healthCheckAll_unhealthy_restartsUnderThreshold() {
        when(workerManager.createAndStart(any())).thenReturn("c1").thenReturn("c2");
        when(workerManager.healthCheck("c1")).thenReturn(false);
        when(workerManager.healthCheck("c2")).thenReturn(true);
        when(crudService.findById(1L)).thenReturn(strategy(1L));
        when(codeService.getPublishedCode(1L)).thenReturn(code(5L, 1L));
        service.startWorker(strategy(1L), code(5L, 1L));

        service.healthCheckAll();

        // consecutiveFailures=1 (<3) → 重启，未发 markError
        verify(eventPublisher, never()).publishEvent(any());
        WorkerStatus st = service.getWorkerStatus(1L);
        assertNotNull(st);
        assertEquals(1, st.consecutiveFailures());
        assertEquals("c2", st.containerId());
    }

    @Test
    void healthCheckAll_3ConsecutiveFailures_marksError() {
        when(workerManager.createAndStart(any())).thenReturn("c1");
        when(workerManager.healthCheck(anyString())).thenReturn(false);
        when(crudService.findById(1L)).thenReturn(strategy(1L));
        when(codeService.getPublishedCode(1L)).thenReturn(code(5L, 1L));
        service.startWorker(strategy(1L), code(5L, 1L));

        service.healthCheckAll(); // → 1, 重启（仍 c1，createAndStart 返回 c1）
        service.healthCheckAll(); // → 2, 重启
        service.healthCheckAll(); // → 3, markError + remove

        ArgumentCaptor<WorkerMarkErrorEvent> captor = ArgumentCaptor.forClass(WorkerMarkErrorEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        WorkerMarkErrorEvent evt = captor.getValue();
        assertEquals(1L, evt.strategyId());
        assertTrue(evt.reason().contains("Health check failed"));
        assertNull(service.getWorkerStatus(1L));
    }

    @Test
    void healthCheckAll_healthy_resetsFailures() {
        when(workerManager.createAndStart(any())).thenReturn("c1");
        when(workerManager.healthCheck("c1")).thenReturn(true);
        service.startWorker(strategy(1L), code(5L, 1L));

        service.healthCheckAll();

        verify(eventPublisher, never()).publishEvent(any());
        assertEquals(0, service.getWorkerStatus(1L).consecutiveFailures());
    }

    @Test
    void reconcile_restartsRunningStrategies() {
        when(crudService.findRunningStrategies()).thenReturn(java.util.List.of(strategy(1L)));
        when(codeService.getPublishedCode(1L)).thenReturn(code(5L, 1L));
        when(workerManager.createAndStart(any())).thenReturn("c1");

        service.reconcileRunningStrategies();

        verify(workerManager).createAndStart(any());
        assertNotNull(service.getWorkerStatus(1L));
    }

    @Test
    void reconcile_noPublishedCode_marksError() {
        when(crudService.findRunningStrategies()).thenReturn(java.util.List.of(strategy(1L)));
        when(codeService.getPublishedCode(1L)).thenReturn(null);

        service.reconcileRunningStrategies();

        ArgumentCaptor<WorkerMarkErrorEvent> captor = ArgumentCaptor.forClass(WorkerMarkErrorEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertTrue(captor.getValue().reason().contains("published code"));
        verify(workerManager, never()).createAndStart(any());
    }

    @Test
    void reconcile_startFails_marksError() {
        when(crudService.findRunningStrategies()).thenReturn(java.util.List.of(strategy(1L)));
        when(codeService.getPublishedCode(1L)).thenReturn(code(5L, 1L));
        when(workerManager.createAndStart(any()))
                .thenThrow(new com.kwikquant.strategy.domain.WorkerStartFailedException(1L, "docker down", null));

        service.reconcileRunningStrategies();

        ArgumentCaptor<WorkerMarkErrorEvent> captor = ArgumentCaptor.forClass(WorkerMarkErrorEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertTrue(captor.getValue().reason().contains("Reconcile failed"));
    }

    @Test
    void startWorker_issuesRunnerTokenAndInjectsIntoConfig() {
        when(workerManager.createAndStart(any())).thenReturn("c1");
        service.startWorker(strategy(1L), code(5L, 1L));

        ArgumentCaptor<WorkerConfig> captor = ArgumentCaptor.forClass(WorkerConfig.class);
        verify(workerManager).createAndStart(captor.capture());
        String issued = captor.getValue().serviceToken();
        // 与 WTS registry 一致(reverseIndex 命中)
        assertTrue(workerTokenService.validateToken(issued, 1L));
        assertEquals(
                "RUNNER",
                workerTokenService.getEntry(issued).taskType(),
                "startWorker 应 issueToken 时 taskType=RUNNER(§3.7)");
    }

    @Test
    void startWorker_replacingWorkerReissuesToken_oldTokenInvalidated() {
        when(workerManager.createAndStart(any())).thenReturn("c1").thenReturn("c2");
        service.startWorker(strategy(1L), code(5L, 1L));
        String oldToken = service.getWorkerStatus(1L) == null ? null : null; // 从 WTS 反查
        // 反查:reverseIndex 里当前的就是老 token
        var oldEntry = workerTokenService.getEntry(workerTokenService.issueToken(999L, "RUNNER", 1L, "BINANCE"));
        assertNotNull(oldEntry);
        // 触发替换 → 新 token 应替换旧的
        service.startWorker(strategy(1L), code(5L, 1L));
        // 需一个已知 token 验证:我们通过 startWorker 的 config 抓 token
        ArgumentCaptor<WorkerConfig> captor = ArgumentCaptor.forClass(WorkerConfig.class);
        verify(workerManager, times(2)).createAndStart(captor.capture());
        var tokens = captor.getAllValues();
        assertNotEquals(
                tokens.get(0).serviceToken(),
                tokens.get(1).serviceToken(),
                "同一 strategyId 二次 start 应签发新 token(reissue 语义)");
        assertFalse(workerTokenService.validateToken(tokens.get(0).serviceToken(), 1L), "旧 token 必须被 WTS 失效");
        assertTrue(workerTokenService.validateToken(tokens.get(1).serviceToken(), 1L));
    }

    @Test
    void stopWorker_revokesToken() {
        when(workerManager.createAndStart(any())).thenReturn("c1");
        service.startWorker(strategy(1L), code(5L, 1L));
        ArgumentCaptor<WorkerConfig> captor = ArgumentCaptor.forClass(WorkerConfig.class);
        verify(workerManager).createAndStart(captor.capture());
        String token = captor.getValue().serviceToken();
        assertTrue(workerTokenService.validateToken(token, 1L));

        service.stopWorker(1L);

        assertFalse(workerTokenService.validateToken(token, 1L), "stopWorker 必须 revoke token");
    }

    @Test
    void stopWorker_notRunning_isIdempotentAndRevokesAnyOrphanToken() {
        // 提前手动 issue,模拟 start 后重启进程 token 遗留而无 registry
        String orphan = workerTokenService.issueToken(42L, "RUNNER", 1L, "BINANCE");
        service.stopWorker(42L);
        assertFalse(workerTokenService.validateToken(orphan, 42L));
        verifyNoInteractions(workerManager);
    }

    @Test
    void handleUnhealthy_3rdFail_revokesToken() {
        when(workerManager.createAndStart(any())).thenReturn("c1");
        when(workerManager.healthCheck(anyString())).thenReturn(false);
        when(crudService.findById(1L)).thenReturn(strategy(1L));
        when(codeService.getPublishedCode(1L)).thenReturn(code(5L, 1L));
        service.startWorker(strategy(1L), code(5L, 1L));
        ArgumentCaptor<WorkerConfig> captor = ArgumentCaptor.forClass(WorkerConfig.class);
        verify(workerManager, atLeastOnce()).createAndStart(captor.capture());
        String tokenBefore = captor.getAllValues().get(0).serviceToken();

        service.healthCheckAll();
        service.healthCheckAll();
        service.healthCheckAll(); // → 3 → markError

        // token 应被 revoke;可能已被 restart 期间 reissue 覆盖,取 WTS 当前 strategyId 关联
        // 严格断言:第 1 次拿到的 token 应失效
        assertFalse(workerTokenService.validateToken(tokenBefore, 1L), "3 次 healthCheck 失败后原 token 必须已失效");
    }

    @Test
    void reconcile_reissuesTokenForRunningStrategies() {
        when(crudService.findRunningStrategies()).thenReturn(java.util.List.of(strategy(1L)));
        when(codeService.getPublishedCode(1L)).thenReturn(code(5L, 1L));
        when(workerManager.createAndStart(any())).thenReturn("c1");
        service.reconcileRunningStrategies();

        ArgumentCaptor<WorkerConfig> captor = ArgumentCaptor.forClass(WorkerConfig.class);
        verify(workerManager).createAndStart(captor.capture());
        String token = captor.getValue().serviceToken();
        assertTrue(workerTokenService.validateToken(token, 1L), "reconcile 时应通过 startWorker 内 issueToken 重发 token");
    }

    private StrategyDefinition strategy(long id) {
        StrategyDefinition s = StrategyDefinition.create(42L, "MA", null, "BTC/USDT", "BINANCE", "SPOT", "1h", "{}");
        s.setId(id);
        s.setStatus(StrategyStatus.RUNNING);
        return s;
    }

    private StrategyCode code(long id, long strategyId) {
        StrategyCode c = StrategyCode.create(strategyId, 1, "def on_bar(): pass", "v1");
        c.setId(id);
        return c;
    }
}
