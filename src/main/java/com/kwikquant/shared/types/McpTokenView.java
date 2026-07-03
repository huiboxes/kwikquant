package com.kwikquant.shared.types;

import java.time.Instant;

/**
 * MCP PAT 脱敏视图（不含 tokenHash/salt，不返明文 token）。供 list 接口返回。
 *
 * @param id token ID
 * @param name 用户自定义别名
 * @param createdAt 创建时间
 * @param lastUsedAt 最后使用时间（nullable）
 * @param expiresAt 过期时间（null=永不过期）
 * @param revokedAt 吊销时间（null=有效）
 */
public record McpTokenView(
        Long id, String name, Instant createdAt, Instant lastUsedAt, Instant expiresAt, Instant revokedAt) {}
