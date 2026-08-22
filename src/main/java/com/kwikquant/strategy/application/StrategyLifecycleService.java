package com.kwikquant.strategy.application;

import com.kwikquant.account.application.ExchangeAccountService;
import com.kwikquant.account.domain.ExchangeAccount;
import com.kwikquant.shared.infra.Auditable;
import com.kwikquant.shared.infra.ResourceStateConflictException;
import com.kwikquant.shared.infra.StrategyExecutionGuard;
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
 * <p><b>不加 {@code @Transactional}</b>：每个方法仅一次 CAS（单语句原子），Worker I/O 必须在
 * 事务外（Docker 调用不持 DB 连接）。CAS auto-commit 后 publishEvent，notification 用
 * {@code @TransactionalEventListener(AFTER_COMMIT, fallbackExecution=true)} 兜底无事务场景。
 *
 * <p><b>start CAS 失败清理</b>：Worker 已在事务外启动，CAS 失败时先 {@code stopWorker} 清理孤儿容器
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
    private final ExchangeAccountService accountService;
    private final StrategyExecutionGuard executionGuard;

    public StrategyLifecycleService(
            StrategyMapper strategyMapper,
            StrategyCrudService crudService,
            StrategyCodeService codeService,
            WorkerOrchestratorService workerService,
            ApplicationEventPublisher eventPublisher,
            ExchangeAccountService accountService,
            StrategyExecutionGuard executionGuard) {
        this.strategyMapper = strategyMapper;
        this.crudService = crudService;
        this.codeService = codeService;
        this.workerService = workerService;
        this.eventPublisher = eventPublisher;
        this.accountService = accountService;
        this.executionGuard = executionGuard;
    }

    public StrategyDefinition ready(long strategyId, long userId) {
        StrategyDefinition s = crudService.getOwned(strategyId, userId);
        requireTransition(s, StrategyStatus.READY, StrategyStatus.DRAFT);
        // 无发布代码的 READY 没有意义（启动时仍会被 7006 拦），在状态转移前就拒绝，
        // 与接口契约（409/7006）保持一致
        if (codeService.getPublishedCode(strategyId) == null) {
            throw new NoPublishedStrategyCodeException(strategyId);
        }
        return casTransition(s, StrategyStatus.READY, userId, false);
    }

    @Auditable(action = "STRATEGY_STARTED", targetType = "strategy", targetId = "#strategyId")
    public StrategyDefinition start(long strategyId, long userId, Long accountId) {
        return transitionToRunning(
                strategyId, userId, accountId, StrategyStatus.READY, StrategyStatus.PAUSED, StrategyStatus.ERROR);
    }

    @Auditable(action = "STRATEGY_RESTARTED", targetType = "strategy", targetId = "#strategyId")
    public StrategyDefinition restart(long strategyId, long userId, Long accountId) {
        return transitionToRunning(strategyId, userId, accountId, StrategyStatus.STOPPED);
    }

    /**
     * start/restart 共用:推进策略到 RUNNING(验账户 + 取发布码 + CAS 占状态 + 绑账户 + 拉 worker)。
     *
     * <p><b>strategy-H3 修复</b>:先 CAS 占状态转移,再绑账户 + 拉 worker。CAS==0(并发状态已变)时
     * 直接抛 conflict,账户绑定尚未执行 → 无遗留(旧:先 updateExchangeAccountId 落库再 CAS,
     * CAS==0 时 stopWorker 只清容器,exchange_account_id 已被换掉不回滚,下次不带 accountId 的
     * start/restart 会落到被换掉的账户上)。Worker 启动失败回滚状态到 previous(CAS RUNNING→previous)。
     *
     * @param allowedFrom 允许转移到 RUNNING 的源状态(start: READY/PAUSED/ERROR;restart: STOPPED)
     */
    private StrategyDefinition transitionToRunning(
            long strategyId, long userId, Long accountId, StrategyStatus... allowedFrom) {
        return executionGuard.transition(
                strategyId, () -> transitionToRunningLocked(strategyId, userId, accountId, allowedFrom));
    }

    private StrategyDefinition transitionToRunningLocked(
            long strategyId, long userId, Long accountId, StrategyStatus... allowedFrom) {
        StrategyDefinition s = crudService.getOwned(strategyId, userId);
        requireTransition(s, StrategyStatus.RUNNING, allowedFrom);
        if (accountId != null) {
            // 切账户/首次绑:验属 user + exchange 匹配(CAS 前只读校验,无副作用)
            ExchangeAccount account = accountService.getOwned(accountId, userId);
            if (!account.getExchange().name().equals(s.getExchange())) {
                throw new IllegalArgumentException(
                        "account exchange " + account.getExchange() + " != strategy exchange " + s.getExchange());
            }
        } else {
            // resume/restart 用已绑账户;未绑(异常)→ 需先选账户启动
            if (s.getExchangeAccountId() == null || s.getExchangeAccountId() == 0) {
                throw new IllegalArgumentException(
                        "strategy " + strategyId + " has no bound account; start/restart with accountId first");
            }
        }
        StrategyCode code = codeService.getPublishedCode(strategyId);
        if (code == null) {
            throw new NoPublishedStrategyCodeException(strategyId);
        }
        StrategyStatus previous = s.getStatus();
        int updated = strategyMapper.updateStatusWithReason(
                strategyId, userId, previous.name(), StrategyStatus.RUNNING.name(), null);
        if (updated == 0) {
            // 状态已被并发改走,直接抛(账户未绑,无遗留 — 旧需 stopWorker 清孤儿,现 startWorker 未执行)
            throw new ResourceStateConflictException("strategy " + strategyId);
        }
        // CAS 成功:绑账户(切账户)+ 拉 worker。Worker 失败回滚状态到 previous。
        if (accountId != null) {
            s.setExchangeAccountId(accountId);
            strategyMapper.updateExchangeAccountId(strategyId, userId, accountId);
        }
        try {
            workerService.startWorker(s, code);
        } catch (RuntimeException e) {
            // Worker 启动失败:回滚状态(CAS RUNNING→previous);账户绑定留(用户意图,原 code 也不回滚)
            strategyMapper.updateStatusWithReason(
                    strategyId, userId, StrategyStatus.RUNNING.name(), previous.name(), null);
            s.setStatus(previous);
            throw e;
        }
        s.setStatus(StrategyStatus.RUNNING);
        publishEvent(userId, strategyId, previous, StrategyStatus.RUNNING);
        return s;
    }

    @Auditable(action = "STRATEGY_STOPPED", targetType = "strategy", targetId = "#strategyId")
    public StrategyDefinition stop(long strategyId, long userId) {
        return executionGuard.transition(strategyId, () -> {
            StrategyDefinition s = crudService.getOwned(strategyId, userId);
            requireTransition(
                    s, StrategyStatus.STOPPED, StrategyStatus.RUNNING, StrategyStatus.PAUSED, StrategyStatus.ERROR);
            StrategyStatus previous = s.getStatus();
            StrategyDefinition stopped = casTransition(s, StrategyStatus.STOPPED, userId, false);
            // 状态先落库；写锁保证已通过二次校验的在途下单全部结束后才进入 STOPPED，
            // 封闭 stop/下单 TOCTOU（与 pause 一致；worker token 吊销只拦新请求，拦不住在途请求）。
            workerService.stopWorker(strategyId);
            publishEvent(userId, strategyId, previous, StrategyStatus.STOPPED);
            return stopped;
        });
    }

    public StrategyDefinition pause(long strategyId, long userId) {
        return executionGuard.transition(strategyId, () -> {
            StrategyDefinition s = crudService.getOwned(strategyId, userId);
            requireTransition(s, StrategyStatus.PAUSED, StrategyStatus.RUNNING);
            StrategyDefinition paused = casTransition(s, StrategyStatus.PAUSED, userId, false);
            // 状态先落库；写锁保证已通过二次校验的下单全部结束后才进入 PAUSED。
            workerService.stopWorker(strategyId);
            publishEvent(userId, strategyId, StrategyStatus.RUNNING, StrategyStatus.PAUSED);
            return paused;
        });
    }

    /**
     * 系统内部调用：将策略标记为 ERROR。跳过状态机（系统强制），CAS 幂等。发布通知事件。
     *
     * <p>写锁保证已通过二次校验的在途 worker 下单全部结束后才进入 ERROR，封闭 markError/下单
     * TOCTOU（与 pause/stop 一致）。markError 只由 WOS 健康检查/对账线程经事件触发，与下单
     * 读锁路径无调用交集，包写锁不会重入死锁。
     */
    public void markError(long strategyId, String reason) {
        executionGuard.transition(strategyId, () -> {
            markErrorLocked(strategyId, reason);
            return null;
        });
    }

    private void markErrorLocked(long strategyId, String reason) {
        StrategyDefinition s = strategyMapper.findById(strategyId);
        if (s == null) {
            log.warn("markError: strategy {} not found, skip", strategyId);
            return;
        }
        StrategyStatus previous = s.getStatus();
        int updated = strategyMapper.updateStatusWithReason(
                strategyId, s.getUserId(), previous.name(), StrategyStatus.ERROR.name(), reason);
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
