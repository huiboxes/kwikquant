package com.kwikquant.risk.domain;

/**
 * Strategy interface for evaluating a single risk rule against a check request.
 *
 * <p>Implementations are discovered by Spring and registered by {@link RiskRuleType}.
 */
public interface RuleEvaluator {

    /**
     * Reason string used when a rule evaluator's internal logic throws — see {@link #evaluate}'s
     * fail-closed contract. Shared so all evaluators (and {@code RiskService}'s own catch-all)
     * report this failure mode identically.
     */
    String INTERNAL_ERROR_REASON = "internal evaluator error";

    /**
     * Returns the rule type this evaluator handles.
     *
     * @return the supported {@link RiskRuleType}
     */
    RiskRuleType supportedType();

    /**
     * Evaluates the given policy against the check request.
     *
     * <p>Implementations must NOT throw exceptions — internal errors must be caught and
     * returned as {@code RuleResult(passed=false, reason=INTERNAL_ERROR_REASON)}.
     * This catch-all contract keeps a single rule's failure from aborting the full
     * evaluation chain; {@link com.kwikquant.risk.application.RiskService} relies on this
     * for fail-closed aggregation.
     *
     * @param policy  the risk policy containing rule parameters
     * @param request the risk check request to evaluate
     * @return the evaluation result
     */
    RuleResult evaluate(RiskPolicy policy, RiskCheckRequest request);
}
