package com.kwikquant.risk.application;

import com.kwikquant.account.application.ExchangeAccountService;
import com.kwikquant.risk.domain.RiskPolicy;
import com.kwikquant.risk.domain.RiskPolicyConflictException;
import com.kwikquant.risk.domain.RiskPolicyNotFoundException;
import com.kwikquant.risk.domain.RiskRuleType;
import com.kwikquant.risk.domain.RuleEvaluator;
import com.kwikquant.risk.domain.evaluators.DailyLossLimitEvaluator;
import com.kwikquant.risk.domain.evaluators.MaxNotionalEvaluator;
import com.kwikquant.risk.domain.evaluators.OrderFrequencyEvaluator;
import com.kwikquant.risk.infrastructure.RiskPolicyMapper;
import com.kwikquant.shared.infra.Auditable;
import java.math.BigDecimal;
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
    private static final int MAX_PARAM_KEYS = 10;
    private static final BigDecimal MAX_NOTIONAL_AMOUNT = new BigDecimal("10000000");
    private static final BigDecimal MAX_LOSS_AMOUNT = new BigDecimal("10000000");
    private static final int MAX_FREQUENCY = 1000;

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
        validateParams(ruleType, params);

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

        validateParams(policy.getRuleType(), params);
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
     * Wave 10 MCP {@code get_risk_rules}（accountId 省略）用：查用户全部策略（单次 SQL，避免 N+1 循环
     * {@link #listByAccount}）。转发 {@link RiskPolicyMapper#findByUserId}（EXISTS 关联 exchange_accounts
     * 校验 owner）。无策略返空列表。
     */
    public List<RiskPolicy> listByUser(long userId) {
        return policyMapper.findByUserId(userId);
    }

    private RiskPolicy requirePolicy(long policyId) {
        RiskPolicy policy = policyMapper.findById(policyId);
        if (policy == null) {
            throw new RiskPolicyNotFoundException(policyId);
        }
        return policy;
    }

    /**
     * Validates rule-specific params. Rejects missing required keys and out-of-range values.
     * Unknown extra keys are logged as warnings but not rejected.
     */
    void validateParams(RiskRuleType ruleType, Map<String, String> params) {
        if (params == null) {
            throw new IllegalArgumentException("params must not be null");
        }
        if (params.size() > MAX_PARAM_KEYS) {
            throw new IllegalArgumentException("params map exceeds maximum of " + MAX_PARAM_KEYS + " keys");
        }

        switch (ruleType) {
            case MAX_NOTIONAL -> validateMaxNotionalParams(params);
            case DAILY_LOSS_LIMIT -> validateDailyLossLimitParams(params);
            case ORDER_FREQUENCY -> validateOrderFrequencyParams(params);
        }
    }

    private void validateMaxNotionalParams(Map<String, String> params) {
        String key = MaxNotionalEvaluator.PARAM_KEY;
        String value = params.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(key + " is required for MAX_NOTIONAL rule");
        }
        BigDecimal amount;
        try {
            amount = new BigDecimal(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be a valid decimal: " + value);
        }
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException(key + " must be > 0");
        }
        if (amount.compareTo(MAX_NOTIONAL_AMOUNT) > 0) {
            throw new IllegalArgumentException(key + " must be <= " + MAX_NOTIONAL_AMOUNT.toPlainString());
        }
        warnUnknownKeys(params, Set.of(key));
    }

    private void validateDailyLossLimitParams(Map<String, String> params) {
        String key = DailyLossLimitEvaluator.PARAM_KEY;
        String value = params.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(key + " is required for DAILY_LOSS_LIMIT rule");
        }
        BigDecimal amount;
        try {
            amount = new BigDecimal(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be a valid decimal: " + value);
        }
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException(key + " must be > 0");
        }
        if (amount.compareTo(MAX_LOSS_AMOUNT) > 0) {
            throw new IllegalArgumentException(key + " must be <= " + MAX_LOSS_AMOUNT.toPlainString());
        }
        warnUnknownKeys(params, Set.of(key));
    }

    private void validateOrderFrequencyParams(Map<String, String> params) {
        String key = OrderFrequencyEvaluator.PARAM_KEY;
        String value = params.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(key + " is required for ORDER_FREQUENCY rule");
        }
        int maxPerMinute;
        try {
            maxPerMinute = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be a valid integer: " + value);
        }
        if (maxPerMinute <= 0) {
            throw new IllegalArgumentException(key + " must be > 0");
        }
        if (maxPerMinute > MAX_FREQUENCY) {
            throw new IllegalArgumentException(key + " must be <= " + MAX_FREQUENCY);
        }
        warnUnknownKeys(params, Set.of(key));
    }

    private void warnUnknownKeys(Map<String, String> params, Set<String> known) {
        for (String key : params.keySet()) {
            if (!known.contains(key)) {
                log.warn("Unknown param key '{}' for risk policy, ignored", key);
            }
        }
    }
}
