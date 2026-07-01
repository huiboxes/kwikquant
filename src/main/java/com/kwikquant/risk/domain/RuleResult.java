package com.kwikquant.risk.domain;

/**
 * Result of evaluating a single risk rule against a check request.
 *
 * @param ruleType the type of rule that was evaluated
 * @param passed   whether the rule passed (true) or was violated (false)
 * @param reason   human-readable reason when the rule failed; null when passed
 */
public record RuleResult(RiskRuleType ruleType, boolean passed, String reason) {}
