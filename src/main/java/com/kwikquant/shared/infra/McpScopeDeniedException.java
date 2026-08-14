package com.kwikquant.shared.infra;

import com.kwikquant.shared.types.McpTokenScope;

/**
 * PAT scope 不足:工具要求某 scope 而当前 PAT 未开通(如 READ-only token 调 submit_order)。
 * 映射 {@link ErrorCode#MCP_SCOPE_DENIED} (10005),HTTP 403。
 *
 * <p>MCP 工具方法经 {@code McpScopeGuard.require} 抛出;scope 是签发时显式开通的最小权限,
 * 与两阶段 confirmToken 构成两层独立防护(scope 管"能不能",confirm 管"这次是否人类确认过")。
 */
public class McpScopeDeniedException extends RuntimeException {

    private final McpTokenScope requiredScope;

    public McpScopeDeniedException(McpTokenScope requiredScope) {
        super("PAT scope insufficient: requires " + requiredScope.name()
                + " (reissue token with this scope via /api/v1/mcp/tokens)");
        this.requiredScope = requiredScope;
    }

    public McpTokenScope requiredScope() {
        return requiredScope;
    }
}
