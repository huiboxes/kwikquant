package com.kwikquant.shared.types;

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
public record McpTokenIssueResult(Long id, String token, String name, Instant createdAt) {

    /** 排除 token 字段，防日志泄露。 */
    @Override
    public String toString() {
        return "McpTokenIssueResult[id=" + id + ", name=" + name + ", createdAt=" + createdAt + "]";
    }
}
