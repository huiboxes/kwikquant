package com.kwikquant.strategy.application;

import com.kwikquant.shared.infra.ResourceStateConflictException;
import com.kwikquant.strategy.domain.IllegalStrategyCodeStateTransitionException;
import com.kwikquant.strategy.domain.StrategyCode;
import com.kwikquant.strategy.domain.StrategyCodeNotFoundException;
import com.kwikquant.strategy.domain.StrategyCodeStatus;
import com.kwikquant.strategy.infrastructure.StrategyCodeMapper;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 策略代码版本管理。DRAFT → PUBLISHED → ARCHIVED 生命周期，同一策略同一时刻最多一个 PUBLISHED 版本。
 *
 * <p>{@code publish} 原子性：归档旧 PUBLISHED + CAS 发布新版本在同一事务内。CAS 失败抛
 * {@link ResourceStateConflictException}。
 *
 * <p><b>偏差（架构师决策）</b>：所有写方法加 {@code userId} 强制所有权校验（tech-design 原接口缺）。
 * {@code getPublishedCode(strategyId)} 不带 userId，仅供 {@link StrategyLifecycleService} 内部调用
 * （该路径已做过 getOwned）。
 */
@Service
public class StrategyCodeService {

    /** source_code 上限 1MB（spec-review S-3）。也被 {@code StrategyCodeController} 的 {@code @Size} 校验引用。 */
    public static final int MAX_SOURCE_SIZE = 1_000_000;

    private final StrategyCodeMapper codeMapper;
    private final StrategyCrudService crudService;

    public StrategyCodeService(StrategyCodeMapper codeMapper, StrategyCrudService crudService) {
        this.codeMapper = codeMapper;
        this.crudService = crudService;
    }

    @Transactional
    public StrategyCode createDraft(long strategyId, long userId, String sourceCode, String changelog) {
        crudService.getOwned(strategyId, userId);
        requireSourceSize(sourceCode);
        // 同时刻最多一个 DRAFT:已有未发布草稿返 409 7005(注释承诺,之前漏实现,致多 DRAFT 共存)
        if (codeMapper.findDraftByStrategyId(strategyId) != null) {
            throw new ResourceStateConflictException("strategy " + strategyId + " has unpublished draft");
        }
        int nextVersion = codeMapper.findMaxVersionNumber(strategyId) + 1;
        StrategyCode code = StrategyCode.create(strategyId, nextVersion, sourceCode, changelog);
        codeMapper.insert(code);
        return code;
    }

    @Transactional
    public StrategyCode updateDraft(long strategyId, long userId, long codeId, String sourceCode, String changelog) {
        crudService.getOwned(strategyId, userId);
        requireSourceSize(sourceCode);
        StrategyCode code = requireOwnedCode(codeId, strategyId);
        if (code.getStatus() != StrategyCodeStatus.DRAFT) {
            throw new IllegalStrategyCodeStateTransitionException(code.getStatus(), code.getStatus());
        }
        // 深度防御消费：updateDraft SQL WHERE 含 status='DRAFT' + EXISTS (strategy owner check)。
        // 返回 0 说明代码版本已并发发布/归档，或 strategy 被软删/owner 变更 → 抛 4009 而非静默返回。
        int updated = codeMapper.updateDraft(codeId, userId, sourceCode, changelog);
        if (updated == 0) {
            throw new ResourceStateConflictException("strategy_code " + codeId);
        }
        code.setSourceCode(sourceCode);
        code.setChangelog(changelog);
        return code;
    }

    @Transactional
    public StrategyCode publish(long strategyId, long userId, long codeId) {
        crudService.getOwned(strategyId, userId);
        StrategyCode code = requireOwnedCode(codeId, strategyId);
        if (code.getStatus() != StrategyCodeStatus.DRAFT) {
            throw new IllegalStrategyCodeStateTransitionException(code.getStatus(), code.getStatus());
        }
        codeMapper.archiveCurrentPublished(strategyId, userId);
        int updated = codeMapper.updateStatus(
                codeId, userId, StrategyCodeStatus.DRAFT.name(), StrategyCodeStatus.PUBLISHED.name());
        if (updated == 0) {
            throw new ResourceStateConflictException("strategy_code " + codeId);
        }
        code.setStatus(StrategyCodeStatus.PUBLISHED);
        return code;
    }

    /** 内部用：返回 PUBLISHED 版本，无则 null（LifecycleService.start 前置校验用）。 */
    public StrategyCode getPublishedCode(long strategyId) {
        return codeMapper.findPublishedByStrategyId(strategyId);
    }

    public List<StrategyCode> listByStrategy(long strategyId, long userId) {
        crudService.getOwned(strategyId, userId);
        return codeMapper.findByStrategyId(strategyId);
    }

    /**
     * 查单个代码版本含 sourceCode 正文（契约改动 A：list 端点不含 sourceCode，前端 Monaco reload 草稿走此端点）。
     * 校验 strategy 所有权 + codeId 归属 strategyId。
     */
    public StrategyCode getOwnedCode(long strategyId, long userId, long codeId) {
        crudService.getOwned(strategyId, userId);
        return requireOwnedCode(codeId, strategyId);
    }

    /** 校验 codeId 属于 strategyId 且存在。不属于时抛 404（不暴露属于其他策略）。 */
    private StrategyCode requireOwnedCode(long codeId, long strategyId) {
        StrategyCode code = codeMapper.findById(codeId);
        if (code == null || code.getStrategyId() != strategyId) {
            throw new StrategyCodeNotFoundException(codeId);
        }
        return code;
    }

    /**
     * 删除代码草稿。仅 DRAFT 可删(放弃当前未发布草稿);PUBLISHED/ARCHIVED 不可删返 409(7005)。
     * 深度防御:deleteDraft SQL WHERE 含 status='DRAFT' + EXISTS owner,返回 0 抛 4009。
     */
    @Transactional
    public void deleteCode(long strategyId, long userId, long codeId) {
        crudService.getOwned(strategyId, userId);
        StrategyCode code = requireOwnedCode(codeId, strategyId);
        if (code.getStatus() != StrategyCodeStatus.DRAFT) {
            throw new IllegalStrategyCodeStateTransitionException(code.getStatus(), code.getStatus());
        }
        int deleted = codeMapper.deleteDraft(codeId, userId);
        if (deleted == 0) {
            throw new ResourceStateConflictException("strategy_code " + codeId);
        }
    }

    private static void requireSourceSize(String sourceCode) {
        // 用 UTF-8 字节数（非 char count），避免含中文注释的源码绕过 1MB 限制（spec-review S-3）
        if (sourceCode != null
                && sourceCode.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_SOURCE_SIZE) {
            throw new IllegalArgumentException("sourceCode exceeds 1MB limit");
        }
    }
}
