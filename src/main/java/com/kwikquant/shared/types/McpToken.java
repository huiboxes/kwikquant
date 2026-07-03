package com.kwikquant.shared.types;

import java.time.Instant;

/**
 * MCP Personal Access Token 实体。
 *
 * <p>PAT 绑 userId，供 AI Agent 长期连接 MCP Server。明文 token 仅 issue 时返回一次；DB 只存
 * {@code tokenHash}（HMAC-SHA-256(raw, pepper) hex，verify 热路径唯一索引）+ {@code salt}
 *（per-token 随机，按 §5.2 schema 保留）。
 *
 * <p>对齐 {@code LlmApiKey}：mutable POJO + 无参构造，供 MyBatis 注解 mapper 行映射。
 * created_at/updated_at 由应用层维护（PostgreSQL 无 ON UPDATE trigger）。
 */
public final class McpToken {

    private Long id;
    private long userId;
    private String name;
    private String tokenHash;
    private String salt;
    private Instant lastUsedAt;
    private Instant expiresAt;
    private Instant revokedAt;
    private Instant createdAt;
    private Instant updatedAt;

    public McpToken() {}

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public long getUserId() {
        return userId;
    }

    public void setUserId(long userId) {
        this.userId = userId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public void setTokenHash(String tokenHash) {
        this.tokenHash = tokenHash;
    }

    public String getSalt() {
        return salt;
    }

    public void setSalt(String salt) {
        this.salt = salt;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }

    public void setLastUsedAt(Instant lastUsedAt) {
        this.lastUsedAt = lastUsedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public void setRevokedAt(Instant revokedAt) {
        this.revokedAt = revokedAt;
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
