package com.kwikquant.risk.domain;

/**
 * Thrown when creating a risk policy conflicts with an existing one (same account + ruleType),
 * i.e. the {@code uk_risk_policies_acct_type} unique index is violated. Mapped to HTTP 409 +
 * {@code RISK_POLICY_CONFLICT(2011)} by {@code RiskExceptionHandler}.
 */
public class RiskPolicyConflictException extends RuntimeException {

    private final long accountId;
    private final String ruleType;

    public RiskPolicyConflictException(long accountId, String ruleType) {
        super("Risk policy already exists for accountId=" + accountId + " ruleType=" + ruleType);
        this.accountId = accountId;
        this.ruleType = ruleType;
    }

    public long getAccountId() {
        return accountId;
    }

    public String getRuleType() {
        return ruleType;
    }
}
