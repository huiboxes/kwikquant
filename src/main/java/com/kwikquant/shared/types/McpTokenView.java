package com.kwikquant.shared.types;

import io.swagger.v3.oas.annotations.media.Schema;
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
        @Schema(description = "token ID", example = "42") Long id,
        @Schema(description = "token 名称", example = "ci-bot-token") String name,
        @Schema(description = "创建时间", example = "2026-07-04T12:00:00Z") Instant createdAt,
        @Schema(description = "最后使用时间，null 表示从未使用", example = "2026-07-04T13:00:00Z") Instant lastUsedAt,
        @Schema(description = "过期时间，null 表示永不过期", example = "2026-08-04T12:00:00Z") Instant expiresAt,
        @Schema(description = "吊销时间，null 表示有效") Instant revokedAt) {}
