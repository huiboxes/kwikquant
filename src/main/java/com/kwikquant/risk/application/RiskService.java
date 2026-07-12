package com.kwikquant.risk.application;

import com.kwikquant.risk.domain.RiskCheckRequest;
import com.kwikquant.risk.domain.RiskDecision;
import com.kwikquant.risk.domain.RiskPolicy;
import com.kwikquant.risk.domain.RiskRuleType;
import com.kwikquant.risk.domain.RiskVerdict;
import com.kwikquant.risk.domain.RuleEvaluator;
import com.kwikquant.risk.domain.RuleResult;
import com.kwikquant.risk.infrastructure.RiskDecisionMapper;
import com.kwikquant.risk.infrastructure.RiskPolicyMapper;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * Core risk check service: evaluates all enabled risk policies for an account
 * and records the decision idempotently.
 */
@Service
public class RiskService {

    private static final Logger log = LoggerFactory.getLogger(RiskService.class);

    private final RiskPolicyMapper policyMapper;
    private final RiskDecisionMapper decisionMapper;
    private final Map<RiskRuleType, RuleEvaluator> evaluatorMap;

    public RiskService(
            RiskPolicyMapper policyMapper, RiskDecisionMapper decisionMapper, List<RuleEvaluator> evaluators) {
        this.policyMapper = policyMapper;
        this.decisionMapper = decisionMapper;

        this.evaluatorMap = new EnumMap<>(RiskRuleType.class);
        for (RuleEvaluator evaluator : evaluators) {
            this.evaluatorMap.put(evaluator.supportedType(), evaluator);
        }
        log.info("RiskService initialized with {} evaluator(s): {}", evaluatorMap.size(), evaluatorMap.keySet());
    }

    /**
     * Pure risk evaluation: reads enabled policies, evaluates ALL rules (no short-circuit),
     * aggregates the verdict, and returns an <b>unpersisted</b> {@link RiskDecision}.
     *
     * <p>No side effects — neither reads the idempotency cache nor inserts the decision.
     * Used by {@link #check} (which persists) and the dry-run preview endpoint (which must not
     * persist, so its verdict stays faithful to what {@code check} would return without the
     * order/freeze/insert side effects of a real submit).
     *
     * @param request the risk check request
     * @return an unpersisted risk decision carrying every rule result
     */
    public RiskDecision evaluate(RiskCheckRequest request) {
        List<RiskPolicy> policies = policyMapper.findEnabledByAccountId(request.accountId());
        List<RuleResult> ruleResults = new ArrayList<>();

        if (policies.isEmpty()) {
            log.debug("No enabled risk policies for accountId={}, auto-approving", request.accountId());
        }

        // Evaluate ALL rules, no short-circuit
        for (RiskPolicy policy : policies) {
            RuleEvaluator evaluator = evaluatorMap.get(policy.getRuleType());
            if (evaluator == null) {
                // Fail-closed: an enabled policy with no registered evaluator is a violation.
                // create() validates ruleType against registered evaluators, so this path is
                // unreachable in normal operation; fail-closed is the defensive default.
                log.warn(
                        "No evaluator registered for enabled rule type {}, marking as failed (fail-closed) for order {}",
                        policy.getRuleType(),
                        request.orderId());
                ruleResults.add(new RuleResult(
                        policy.getRuleType(), false, "no evaluator registered for " + policy.getRuleType()));
                continue;
            }
            try {
                RuleResult result = evaluator.evaluate(policy, request);
                ruleResults.add(result);
            } catch (Exception e) {
                log.error(
                        "Evaluator {} threw unexpected exception for order {}: {}",
                        policy.getRuleType(),
                        request.orderId(),
                        e.getMessage(),
                        e);
                ruleResults.add(new RuleResult(policy.getRuleType(), false, "internal evaluator error"));
            }
        }

        // Aggregate: any failure -> REJECTED
        boolean anyFailed = ruleResults.stream().anyMatch(r -> !r.passed());
        RiskVerdict verdict = anyFailed ? RiskVerdict.REJECTED : RiskVerdict.APPROVED;

        RiskDecision decision = new RiskDecision();
        decision.setRequestId(request.requestId());
        decision.setOrderId(request.orderId());
        decision.setAccountId(request.accountId());
        decision.setVerdict(verdict);
        decision.setRuleResults(ruleResults);
        return decision;
    }

    /**
     * Performs a pre-trade risk check for the given request.
     *
     * <p>Idempotent: if a decision already exists for the same {@code requestId}, it is returned as-is.
     * All enabled rules are evaluated (no short-circuit on first failure). The decision is persisted.
     *
     * @param request the risk check request
     * @return the risk decision
     */
    public RiskDecision check(RiskCheckRequest request) {
        // Idempotent short-circuit
        RiskDecision existing = decisionMapper.findByRequestId(request.requestId());
        if (existing != null) {
            log.debug("Risk check idempotent hit for requestId={}", request.requestId());
            return existing;
        }

        RiskDecision decision = evaluate(request);

        try {
            decisionMapper.insert(decision);
        } catch (DuplicateKeyException e) {
            // Concurrent insert race: return the winner's decision
            log.debug("Concurrent risk decision insert for requestId={}, fetching existing", request.requestId());
            return decisionMapper.findByRequestId(request.requestId());
        }

        log.info(
                "Risk check completed: orderId={}, requestId={}, verdict={}, rulesEvaluated={}",
                decision.getOrderId(),
                decision.getRequestId(),
                decision.getVerdict(),
                decision.getRuleResults().size());
        return decision;
    }
}
