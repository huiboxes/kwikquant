package com.kwikquant.risk.domain;

/**
 * Thrown when a risk policy is not found by its id.
 */
public class RiskPolicyNotFoundException extends RuntimeException {

    private final long policyId;

    public RiskPolicyNotFoundException(long policyId) {
        super("Risk policy not found: " + policyId);
        this.policyId = policyId;
    }

    public long getPolicyId() {
        return policyId;
    }
}
