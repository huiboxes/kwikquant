package com.kwikquant.shared.types;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * MCP PAT 签发结果（issue 接口返回）。含明文 token —— 明文仅此一次返回，DB 只存哈希。
 *
 * <p>{@link #toString()} 重写以排除 {@code token} 字段，防止日志/调试意外泄露明文 token（§6 安全要求）。
 *
 * @param id token ID
 * @param token 明文 token（{@code kq_pat_<32hex>}），仅 issue 时返回，调用方须立即保存
 * @param name 用户自定义别名
 * @param createdAt 创建时间
 */
public record McpTokenIssueResult(
        @Schema(description = "token ID", example = "42") Long id,
        @Schema(
                        description = "明文 token（kq_pat_<32hex>），**仅此响应可见，请即保存**，DB 只存哈希",
                        example = "kq_pat_3f5a1b2c4d8e9a0f1b2c3d4e5f6a7b8c")
                String token,
        @Schema(description = "token 名称", example = "ci-bot-token") String name,
        @Schema(description = "创建时间", example = "2026-07-04T12:00:00Z") Instant createdAt) {

    /** 排除 token 字段，防日志泄露。 */
    @Override
    public String toString() {
        return "McpTokenIssueResult[id=" + id + ", name=" + name + ", createdAt=" + createdAt + "]";
    }
}
