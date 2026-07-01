package com.kwikquant.risk.domain;

/**
 * Strategy interface for evaluating a single risk rule against a check request.
 *
 * <p>Implementations are discovered by Spring and registered by {@link RiskRuleType}.
 */
public interface RuleEvaluator {

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
     * returned as {@code RuleResult(passed=false, reason="internal evaluator error")}.
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
