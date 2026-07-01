package com.kwikquant.risk.domain;

import java.time.Instant;
import java.util.Map;

/**
 * Mutable entity representing a configured risk policy for an exchange account.
 *
 * <p>Each account may have at most one policy per {@link RiskRuleType} (enforced by the DB unique index).
 */
public final class RiskPolicy {

    private Long id;
    private long accountId;
    private RiskRuleType ruleType;
    private String name;
    private Map<String, String> params;
    private boolean enabled;
    private Instant createdAt;
    private Instant updatedAt;

    public RiskPolicy() {}

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public long getAccountId() {
        return accountId;
    }

    public void setAccountId(long accountId) {
        this.accountId = accountId;
    }

    public RiskRuleType getRuleType() {
        return ruleType;
    }

    public void setRuleType(RiskRuleType ruleType) {
        this.ruleType = ruleType;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Map<String, String> getParams() {
        return params;
    }

    public void setParams(Map<String, String> params) {
        this.params = params;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
