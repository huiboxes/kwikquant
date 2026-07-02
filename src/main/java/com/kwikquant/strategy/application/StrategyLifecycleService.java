package com.kwikquant.strategy.application;

import com.kwikquant.shared.infra.Auditable;
import com.kwikquant.shared.infra.ResourceStateConflictException;
import com.kwikquant.shared.types.StrategyId;
import com.kwikquant.shared.types.StrategyStatus;
import com.kwikquant.shared.types.StrategyStatusChangedEvent;
import com.kwikquant.strategy.domain.IllegalStrategyStateTransitionException;
import com.kwikquant.strategy.domain.NoPublishedStrategyCodeException;
import com.kwikquant.strategy.domain.StrategyCode;
import com.kwikquant.strategy.domain.StrategyDefinition;
import com.kwikquant.strategy.infrastructure.StrategyMapper;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * 策略生命周期：ready/start/stop/pause 状态转换 + Worker 编排 + 通知事件发布。
 *
 * <p><b>不加 {@code @Transactional}（架构师决策）</b>：每个方法仅一次 CAS（单语句原子），Worker I/O 必须在
 * 事务外（Docker 调用不持 DB 连接）。CAS auto-commit 后 publishEvent，notification 用
 * {@code @TransactionalEventListener(AFTER_COMMIT, fallbackExecution=true)} 兜底无事务场景。
 *
 * <p><b>start CAS 失败清理</b>（MAJ-9）：Worker 已在事务外启动，CAS 失败时先 {@code stopWorker} 清理孤儿容器
 * 再抛 {@link ResourceStateConflictException}。
 *
 * <p><b>markError</b>：系统内部调用（WOS 健康检查 3 次失败 → 发 {@link WorkerMarkErrorEvent} → 本服务监听）。
 * 跳过状态机校验（系统强制 ERROR），CAS 幂等（0 行=已 ERROR，不抛）。
 */
@Service
public class StrategyLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(StrategyLifecycleService.class);

    private final StrategyMapper strategyMapper;
    private final StrategyCrudService crudService;
    private final StrategyCodeService codeService;
    private final WorkerOrchestratorService workerService;
    private final ApplicationEventPublisher eventPublisher;

    public StrategyLifecycleService(
            StrategyMapper strategyMapper,
            StrategyCrudService crudService,
            StrategyCodeService codeService,
            WorkerOrchestratorService workerService,
            ApplicationEventPublisher eventPublisher) {
        this.strategyMapper = strategyMapper;
        this.crudService = crudService;
        this.codeService = codeService;
        this.workerService = workerService;
        this.eventPublisher = eventPublisher;
    }

    public StrategyDefinition ready(long strategyId, long userId) {
        StrategyDefinition s = crudService.getOwned(strategyId, userId);
        requireTransition(s, StrategyStatus.READY, StrategyStatus.DRAFT);
        return casTransition(s, StrategyStatus.READY, userId, false);
    }

    @Auditable(action = "STRATEGY_STARTED", targetType = "strategy", targetId = "#strategyId")
    public StrategyDefinition start(long strategyId, long userId) {
        StrategyDefinition s = crudService.getOwned(strategyId, userId);
        requireTransition(s, StrategyStatus.RUNNING, StrategyStatus.READY, StrategyStatus.PAUSED);
        StrategyCode code = codeService.getPublishedCode(strategyId);
        if (code == null) {
            throw new NoPublishedStrategyCodeException(strategyId);
        }
        workerService.startWorker(s, code);
        int updated =
                strategyMapper.updateStatus(strategyId, userId, s.getStatus().name(), StrategyStatus.RUNNING.name());
        if (updated == 0) {
            workerService.stopWorker(strategyId); // 清理孤儿 Worker（MAJ-9）
            throw new ResourceStateConflictException("strategy " + strategyId);
        }
        StrategyStatus previous = s.getStatus();
        s.setStatus(StrategyStatus.RUNNING);
        publishEvent(userId, strategyId, previous, StrategyStatus.RUNNING);
        return s;
    }

    @Auditable(action = "STRATEGY_STOPPED", targetType = "strategy", targetId = "#strategyId")
    public StrategyDefinition stop(long strategyId, long userId) {
        StrategyDefinition s = crudService.getOwned(strategyId, userId);
        requireTransition(
                s, StrategyStatus.STOPPED, StrategyStatus.RUNNING, StrategyStatus.PAUSED, StrategyStatus.ERROR);
        workerService.stopWorker(strategyId);
        return casTransition(s, StrategyStatus.STOPPED, userId, true);
    }

    public StrategyDefinition pause(long strategyId, long userId) {
        StrategyDefinition s = crudService.getOwned(strategyId, userId);
        requireTransition(s, StrategyStatus.PAUSED, StrategyStatus.RUNNING);
        // pause 不停 Worker 进程，仅状态标记（Worker 下单时 OrderRouter 检查 status==RUNNING 否则拒绝）
        return casTransition(s, StrategyStatus.PAUSED, userId, true);
    }

    /**
     * 系统内部调用：将策略标记为 ERROR。跳过状态机（系统强制），CAS 幂等。发布通知事件。
     */
    public void markError(long strategyId, String reason) {
        StrategyDefinition s = strategyMapper.findById(strategyId);
        if (s == null) {
            log.warn("markError: strategy {} not found, skip", strategyId);
            return;
        }
        StrategyStatus previous = s.getStatus();
        int updated =
                strategyMapper.updateStatus(strategyId, s.getUserId(), previous.name(), StrategyStatus.ERROR.name());
        if (updated == 0) {
            log.debug("markError: strategy {} CAS failed (concurrent change), skip", strategyId);
            return;
        }
        s.setStatus(StrategyStatus.ERROR);
        publishEvent(s.getUserId(), strategyId, previous, StrategyStatus.ERROR);
    }

    /** 监听 WOS 健康检查失败事件，转发到 markError（打破循环依赖）。 */
    @EventListener(WorkerMarkErrorEvent.class)
    public void onWorkerMarkError(WorkerMarkErrorEvent event) {
        markError(event.strategyId(), event.reason());
    }

    private StrategyDefinition casTransition(
            StrategyDefinition s, StrategyStatus target, long userId, boolean publish) {
        StrategyStatus previous = s.getStatus();
        int updated = strategyMapper.updateStatus(s.getId(), userId, previous.name(), target.name());
        if (updated == 0) {
            throw new ResourceStateConflictException("strategy " + s.getId());
        }
        s.setStatus(target);
        if (publish) {
            publishEvent(userId, s.getId(), previous, target);
        }
        return s;
    }

    private void publishEvent(long userId, long strategyId, StrategyStatus previous, StrategyStatus target) {
        eventPublisher.publishEvent(
                new StrategyStatusChangedEvent(userId, new StrategyId(strategyId), previous, target, Instant.now()));
    }

    private static void requireTransition(StrategyDefinition s, StrategyStatus target, StrategyStatus... allowedFrom) {
        for (StrategyStatus allowed : allowedFrom) {
            if (s.getStatus() == allowed) {
                return;
            }
        }
        throw new IllegalStrategyStateTransitionException(s.getStatus(), target);
    }
}
