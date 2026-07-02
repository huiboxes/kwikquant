package com.kwikquant.strategy.application;

import com.kwikquant.shared.infra.OwnershipCheck;
import com.kwikquant.shared.infra.ResourceStateConflictException;
import com.kwikquant.shared.types.StrategyStatus;
import com.kwikquant.strategy.domain.IllegalStrategyStateTransitionException;
import com.kwikquant.strategy.domain.StrategyDefinition;
import com.kwikquant.strategy.domain.StrategyNotFoundException;
import com.kwikquant.strategy.infrastructure.StrategyMapper;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 策略 CRUD 服务。所有权校验 + 更新/删除前置状态校验（仅 DRAFT/STOPPED 可编辑）。
 *
 * <p><b>与 tech-design §3.3 的偏差（架构师决策）</b>：{@code delete} 签名加 {@code userId} 强制所有权校验
 * （参照 {@code ExchangeAccountService.delete}），tech-design 原 {@code delete(strategyId)} 缺所有权校验。
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
            String intervalValue,
            String parameters) {
        StrategyDefinition s = StrategyDefinition.create(
                userId, name, description, symbol, exchange, marketType, intervalValue, parameters);
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

    @Transactional
    public StrategyDefinition update(
            long strategyId,
            long userId,
            String name,
            String description,
            String symbol,
            String exchange,
            String marketType,
            String intervalValue,
            String parameters) {
        StrategyDefinition s = getOwned(strategyId, userId);
        requireEditable(s);
        s.setName(name);
        s.setDescription(description);
        s.setSymbol(symbol);
        s.setExchange(exchange);
        s.setMarketType(marketType);
        s.setIntervalValue(intervalValue);
        s.setParameters(parameters);
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
        requireEditable(s);
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

    private static void requireEditable(StrategyDefinition s) {
        StrategyStatus st = s.getStatus();
        if (st != StrategyStatus.DRAFT && st != StrategyStatus.STOPPED) {
            throw new IllegalStrategyStateTransitionException(st, st);
        }
    }
}
