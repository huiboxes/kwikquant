package com.kwikquant.shared.types;

import java.util.Set;

/**
 * PAT 验证结果:身份 + 权限域。{@code McpTokenService.verify} 返回(null=无效),
 * filter 据此注入 SecurityContext(principal=userId,authorities=SCOPE_*)。
 *
 * @param userId 令牌属主
 * @param scopes 已开通权限域(不可变;空集 = 无任何能力,fail-closed)
 */
public record McpTokenPrincipal(long userId, Set<McpTokenScope> scopes) {

    public McpTokenPrincipal {
        scopes = Set.copyOf(scopes);
    }

    public boolean hasScope(McpTokenScope scope) {
        return scopes.contains(scope);
    }
}
