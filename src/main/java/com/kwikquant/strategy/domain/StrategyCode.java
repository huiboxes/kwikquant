package com.kwikquant.strategy.domain;

import java.time.Instant;

/**
 * 策略代码版本实体。每次代码修改产生新版本号，支持 DRAFT -> PUBLISHED -> ARCHIVED 生命周期。
 *
 * <p>状态推进通过 {@link #transitionTo(StrategyCodeStatus)} 进行。
 */
public class StrategyCode {

    private static final String DEFAULT_LANGUAGE = "python";

    private Long id;
    private long strategyId;
    private int versionNumber;
    private String sourceCode;
    private StrategyCodeStatus status;
    private String language;
    private String changelog;
    private Instant createdAt;
    private Instant updatedAt;

    public StrategyCode() {}

    /**
     * 工厂方法：创建 DRAFT 状态的策略代码版本。
     *
     * @param strategyId 所属策略 ID
     * @param versionNumber 版本号
     * @param sourceCode 源代码内容
     * @param changelog 变更说明
     * @return DRAFT 状态的新策略代码版本
     */
    public static StrategyCode create(long strategyId, int versionNumber, String sourceCode, String changelog) {
        StrategyCode c = new StrategyCode();
        c.strategyId = strategyId;
        c.versionNumber = versionNumber;
        c.sourceCode = sourceCode;
        c.status = StrategyCodeStatus.DRAFT;
        c.language = DEFAULT_LANGUAGE;
        c.changelog = changelog;
        return c;
    }

    /**
     * 状态推进。违反状态机抛异常。
     *
     * @param target 目标状态
     * @throws IllegalStrategyCodeStateTransitionException 非法状态转换时抛出
     */
    public void transitionTo(StrategyCodeStatus target) {
        if (status == null || !status.canTransitionTo(target)) {
            throw new IllegalStrategyCodeStateTransitionException(status, target);
        }
        this.status = target;
    }

    // ---------- getters / setters ----------

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public long getStrategyId() {
        return strategyId;
    }

    public void setStrategyId(long strategyId) {
        this.strategyId = strategyId;
    }

    public int getVersionNumber() {
        return versionNumber;
    }

    public void setVersionNumber(int versionNumber) {
        this.versionNumber = versionNumber;
    }

    public String getSourceCode() {
        return sourceCode;
    }

    public void setSourceCode(String sourceCode) {
        this.sourceCode = sourceCode;
    }

    public StrategyCodeStatus getStatus() {
        return status;
    }

    public void setStatus(StrategyCodeStatus status) {
        this.status = status;
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public String getChangelog() {
        return changelog;
    }

    public void setChangelog(String changelog) {
        this.changelog = changelog;
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
