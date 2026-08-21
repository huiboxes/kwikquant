package com.kwikquant.risk.application;

import com.kwikquant.account.application.ExchangeAccountService;
import com.kwikquant.risk.domain.RiskPolicy;
import com.kwikquant.risk.domain.RiskPolicyConflictException;
import com.kwikquant.risk.domain.RiskPolicyNotFoundException;
import com.kwikquant.risk.domain.RiskRuleType;
import com.kwikquant.risk.domain.RuleEvaluator;
import com.kwikquant.risk.infrastructure.RiskPolicyMapper;
import com.kwikquant.shared.infra.Auditable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manages risk policy CRUD: create, update, toggle, delete, and list.
 *
 * <p>All mutating operations perform an ownership check via {@link ExchangeAccountService#getOwned}.
 */
@Service
public class RiskPolicyManagementService {

    private static final Logger log = LoggerFactory.getLogger(RiskPolicyManagementService.class);

    private static final String TARGET_TYPE = "risk_policy";

    private final RiskPolicyMapper policyMapper;
    private final ExchangeAccountService exchangeAccountService;
    private final Set<RiskRuleType> supportedTypes;

    public RiskPolicyManagementService(
            RiskPolicyMapper policyMapper,
            ExchangeAccountService exchangeAccountService,
            List<RuleEvaluator> evaluators) {
        this.policyMapper = policyMapper;
        this.exchangeAccountService = exchangeAccountService;
        this.supportedTypes =
                evaluators.stream().map(RuleEvaluator::supportedType).collect(Collectors.toSet());
        log.info("RiskPolicyManagementService initialized, supported rule types: {}", supportedTypes);
    }

    /**
     * Creates a new risk policy for the specified account.
     *
     * @param accountId     the exchange account id
     * @param currentUserId the current user id (for ownership check)
     * @param ruleType      the risk rule type
     * @param name          human-readable name for the policy
     * @param params        rule-specific parameters
     * @return the created policy with generated id
     * @throws IllegalArgumentException if params are invalid or ruleType is unsupported
     */
    @Transactional
    @Auditable(action = "RISK_POLICY_CREATED", targetType = TARGET_TYPE, targetId = "#accountId")
    public RiskPolicy create(
            long accountId, long currentUserId, RiskRuleType ruleType, String name, Map<String, String> params) {
        exchangeAccountService.getOwned(accountId, currentUserId);

        if (!supportedTypes.contains(ruleType)) {
            throw new IllegalArgumentException("Unsupported rule type: " + ruleType);
        }
        RiskPolicyParamValidator.validate(ruleType, params);

        RiskPolicy policy = new RiskPolicy();
        policy.setAccountId(accountId);
        policy.setRuleType(ruleType);
        policy.setName(name);
        policy.setParams(params);
        policy.setEnabled(true);
        try {
            policyMapper.insert(policy);
        } catch (DataIntegrityViolationException e) {
            // uk_risk_policies_acct_type conflict — this account already has a policy for this ruleType.
            throw new RiskPolicyConflictException(accountId, ruleType.name());
        }

        log.info("Created risk policy id={} type={} for accountId={}", policy.getId(), ruleType, accountId);
        return policy;
    }

    /**
     * Updates an existing risk policy's name and params.
     *
     * @param policyId      the policy id
     * @param currentUserId the current user id (for ownership check)
     * @param name          updated name
     * @param params        updated parameters
     * @return the updated policy
     */
    @Transactional
    @Auditable(action = "RISK_POLICY_UPDATED", targetType = TARGET_TYPE, targetId = "#policyId")
    public RiskPolicy update(long policyId, long currentUserId, String name, Map<String, String> params) {
        RiskPolicy policy = requirePolicy(policyId);
        exchangeAccountService.getOwned(policy.getAccountId(), currentUserId);

        RiskPolicyParamValidator.validate(policy.getRuleType(), params);
        policy.setName(name);
        policy.setParams(params);
        int updated = policyMapper.updateNameAndParamsWithOwner(policy, currentUserId);
        if (updated == 0) {
            // 深度防御触发（policy 关联 account 的 owner 变更 / 并发删除）
            throw new com.kwikquant.shared.infra.ResourceStateConflictException(TARGET_TYPE + " " + policyId);
        }

        log.info("Updated risk policy id={}", policyId);
        return policy;
    }

    /**
     * Toggles a risk policy's enabled state.
     *
     * @param policyId      the policy id
     * @param currentUserId the current user id (for ownership check)
     * @param enabled       the new enabled state
     * @return the updated policy
     */
    @Transactional
    @Auditable(action = "RISK_POLICY_TOGGLED", targetType = TARGET_TYPE, targetId = "#policyId")
    public RiskPolicy toggle(long policyId, long currentUserId, boolean enabled) {
        RiskPolicy policy = requirePolicy(policyId);
        exchangeAccountService.getOwned(policy.getAccountId(), currentUserId);

        policy.setEnabled(enabled);
        int updated = policyMapper.updateEnabledWithOwner(policyId, enabled, currentUserId);
        if (updated == 0) {
            throw new com.kwikquant.shared.infra.ResourceStateConflictException(TARGET_TYPE + " " + policyId);
        }

        log.info("Toggled risk policy id={} enabled={}", policyId, enabled);
        return policy;
    }

    /**
     * Deletes a risk policy.
     *
     * @param policyId      the policy id
     * @param currentUserId the current user id (for ownership check)
     */
    @Transactional
    @Auditable(action = "RISK_POLICY_DELETED", targetType = TARGET_TYPE, targetId = "#policyId")
    public void delete(long policyId, long currentUserId) {
        RiskPolicy policy = requirePolicy(policyId);
        exchangeAccountService.getOwned(policy.getAccountId(), currentUserId);

        int deleted = policyMapper.deleteByIdWithOwner(policyId, currentUserId);
        if (deleted == 0) {
            throw new com.kwikquant.shared.infra.ResourceStateConflictException(TARGET_TYPE + " " + policyId);
        }
        log.info("Deleted risk policy id={}", policyId);
    }

    /**
     * Lists all risk policies for the specified account.
     *
     * @param accountId     the exchange account id
     * @param currentUserId the current user id (for ownership check)
     * @return list of risk policies (enabled and disabled)
     */
    public List<RiskPolicy> listByAccount(long accountId, long currentUserId) {
        exchangeAccountService.getOwned(accountId, currentUserId);
        return policyMapper.findByAccountId(accountId);
    }

    /**
     * MCP {@code get_risk_rules}（accountId 省略）用：查用户全部策略（单次 SQL，避免 N+1 循环
     * {@link #listByAccount}）。转发 {@link RiskPolicyMapper#findByUserId}（EXISTS 关联 exchange_accounts
     * 校验 owner）。无策略返空列表。
     */
    public List<RiskPolicy> listByUser(long userId) {
        return policyMapper.findByUserId(userId);
    }

    /**
     * 批量应用风控策略(自然语言风控"确认后落库"编排):逐条 create-or-update,单事务原子提交——
     * 任一条失败(冲突/参数非法/归属不符)整体回滚,避免风控配置落到"半生效"状态。
     *
     * <p>item.policyId 非空 → 覆盖更新该策略(校验其归属本 accountId,防跨账户错配);空 → 新建
     * (同账户同 ruleType 已存在时 {@link #create} 抛 {@link RiskPolicyConflictException})。
     *
     * <p>审计:内部 create/update 经 this 自调用不过 AOP 代理,各自的 {@code @Auditable} 不触发;
     * 由本方法 {@code RISK_POLICY_APPLIED} 单条批量审计覆盖(粒度换原子性,落库明细可查 risk_policies)。
     *
     * @param accountId     目标账户(归属校验一次)
     * @param currentUserId 当前用户
     * @param items         应用指令列表(≥1)
     * @return 按入参顺序的落库策略列表
     */
    @Transactional
    @Auditable(action = "RISK_POLICY_APPLIED", targetType = TARGET_TYPE, targetId = "#accountId")
    public List<RiskPolicy> applyBulk(long accountId, long currentUserId, List<RiskPolicyApplyItem> items) {
        exchangeAccountService.getOwned(accountId, currentUserId);
        List<RiskPolicy> applied = new ArrayList<>();
        for (RiskPolicyApplyItem item : items) {
            if (item.policyId() != null) {
                RiskPolicy existing = requirePolicy(item.policyId());
                if (existing.getAccountId() != accountId) {
                    throw new IllegalArgumentException(
                            "policyId " + item.policyId() + " does not belong to account " + accountId);
                }
                applied.add(update(item.policyId(), currentUserId, item.name(), item.params()));
            } else {
                applied.add(create(accountId, currentUserId, item.ruleType(), item.name(), item.params()));
            }
        }
        log.info("Applied {} risk policies to accountId={} via bulk apply", applied.size(), accountId);
        return applied;
    }

    private RiskPolicy requirePolicy(long policyId) {
        RiskPolicy policy = policyMapper.findById(policyId);
        if (policy == null) {
            throw new RiskPolicyNotFoundException(policyId);
        }
        return policy;
    }
}
