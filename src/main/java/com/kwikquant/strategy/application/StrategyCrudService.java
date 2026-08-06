package com.kwikquant.strategy.application;

import com.kwikquant.shared.infra.OwnershipCheck;
import com.kwikquant.shared.infra.ResourceStateConflictException;
import com.kwikquant.shared.types.StrategyStatus;
import com.kwikquant.strategy.domain.StrategyDefinition;
import com.kwikquant.strategy.domain.StrategyNotEditableException;
import com.kwikquant.strategy.domain.StrategyNotFoundException;
import com.kwikquant.strategy.infrastructure.StrategyMapper;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 策略 CRUD 服务。所有权校验 + 更新/删除前置可编辑性校验(update 仅 DRAFT/STOPPED;delete DRAFT/READY/STOPPED,均无活跃 worker)。
 *
 * <p>{@code delete} 签名加 {@code userId} 强制所有权校验
 * （参照 {@code ExchangeAccountService.delete}）。
 * 新增 {@link #findById}（内部系统调用，无 HTTP 上下文）和 {@link #findRunningStrategies}（应用重启 reconcile 用）。
 */
@Service
public class StrategyCrudService {

    private final StrategyMapper mapper;

    public StrategyCrudService(StrategyMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional
    public StrategyDefinition create(
            long userId,
            String name,
            String description,
            String symbol,
            String exchange,
            String marketType,
            String marginMode,
            Integer leverage,
            String intervalValue,
            String parameters) {
        StrategyDefinition s = StrategyDefinition.create(
                userId, name, description, symbol, exchange, marketType, intervalValue, parameters);
        s.setMarginMode(marginMode);
        s.setLeverage(leverage);
        mapper.insert(s);
        return s;
    }

    public StrategyDefinition getOwned(long strategyId, long userId) {
        StrategyDefinition s = mapper.findById(strategyId);
        if (s == null) {
            throw new StrategyNotFoundException(strategyId);
        }
        return OwnershipCheck.requireOwned(s, s.getUserId(), userId, "strategy");
    }

    /** 内部用：无所有权校验（系统调用，如 LifecycleService.markError 已知上下文）。 */
    public StrategyDefinition findById(long strategyId) {
        StrategyDefinition s = mapper.findById(strategyId);
        if (s == null) {
            throw new StrategyNotFoundException(strategyId);
        }
        return s;
    }

    public List<StrategyDefinition> listByUser(long userId) {
        return mapper.findByUserId(userId);
    }

    /** 返回用户最近编辑的策略（按 updated_at DESC LIMIT 1），无策略时返回 null。 */
    public StrategyDefinition getLastEdited(long userId) {
        List<StrategyDefinition> list = mapper.findByUserIdOrderByUpdatedAtDesc(userId, 1);
        return list.isEmpty() ? null : list.get(0);
    }

    @Transactional
    public StrategyDefinition update(
            long strategyId,
            long userId,
            String name,
            String description,
            String symbol,
            String exchange,
            String marketType,
            String marginMode,
            Integer leverage,
            String intervalValue,
            String parameters,
            String version) {
        StrategyDefinition s = getOwned(strategyId, userId);
        requireUpdatable(s);
        s.setName(name);
        s.setDescription(description);
        s.setSymbol(symbol);
        s.setExchange(exchange);
        s.setMarketType(marketType);
        s.setMarginMode(marginMode);
        s.setLeverage(leverage);
        s.setIntervalValue(intervalValue);
        s.setParameters(parameters);
        s.setVersion(version);
        // 深度防御消费：mapper.update WHERE 含 user_id + deleted=FALSE，返回 0 说明并发已删除或
        // owner 校验失败 → 抛 4009 而非静默返回旧快照
        int updated = mapper.update(s);
        if (updated == 0) {
            throw new ResourceStateConflictException("strategy " + strategyId);
        }
        return s;
    }

    @Transactional
    public void delete(long strategyId, long userId) {
        StrategyDefinition s = getOwned(strategyId, userId);
        requireDeletable(s);
        // 深度防御消费：softDelete WHERE 含 user_id + deleted=FALSE，返回 0 = 并发已删或非 owner
        int deleted = mapper.softDelete(strategyId, userId);
        if (deleted == 0) {
            throw new ResourceStateConflictException("strategy " + strategyId);
        }
    }

    /** 应用重启 reconcile 用：查所有 RUNNING 策略以重建 Worker Registry。 */
    public List<StrategyDefinition> findRunningStrategies() {
        return mapper.findByStatus(StrategyStatus.RUNNING.name());
    }

    /**
     * update 前置可编辑性:仅 DRAFT/STOPPED 可改配置(已发布/运行的不改配置,破坏一致性)。
     * 非状态机转换(无目标态),抛 {@link StrategyNotEditableException}(7007),与 lifecycle 7002 区分。
     */
    private static void requireUpdatable(StrategyDefinition s) {
        StrategyStatus st = s.getStatus();
        if (st != StrategyStatus.DRAFT && st != StrategyStatus.STOPPED) {
            throw new StrategyNotEditableException(st, "编辑");
        }
    }

    /**
     * delete 前置可编辑性:DRAFT/READY/STOPPED 可删(均无活跃 worker);
     * RUNNING/PAUSED/ERROR 必须先 stop(PAUSED 进程在;ERROR:markError 不 stopWorker 容器可能残留)。
     */
    private static void requireDeletable(StrategyDefinition s) {
        StrategyStatus st = s.getStatus();
        if (st != StrategyStatus.DRAFT && st != StrategyStatus.READY && st != StrategyStatus.STOPPED) {
            throw new StrategyNotEditableException(st, "删除");
        }
    }
}
