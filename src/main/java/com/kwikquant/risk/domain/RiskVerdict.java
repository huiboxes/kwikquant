package com.kwikquant.risk.domain;

/**
 * Verdict of a risk check: the order is either approved or rejected.
 *
 * <p>BYPASSED is intentionally not a verdict — it is an audit action, not a risk decision outcome.
 */
public enum RiskVerdict {
    APPROVED,
    REJECTED,
}
