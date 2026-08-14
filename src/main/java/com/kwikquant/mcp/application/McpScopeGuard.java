package com.kwikquant.mcp.application;

import com.kwikquant.shared.infra.McpScopeDeniedException;
import com.kwikquant.shared.types.McpTokenScope;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * MCP PAT scope 守卫。{@link com.kwikquant.shared.infra.McpTokenAuthenticationFilter} 把 PAT 的
 * scopes 注入 SecurityContext authorities({@code SCOPE_<NAME>});写/高危工具入口调
 * {@link #require(McpTokenScope)},未开通抛 {@link McpScopeDeniedException}(10005)。
 *
 * <p>fail-closed:无 Authentication 或 authorities 缺失一律拒绝(/mcp 路径 filter 保证必有,
 * 此处为深度防御)。scope 管"能不能",两阶段 confirmToken 管"这次是否确认过",两层独立。
 */
@Component
public class McpScopeGuard {

    /** 与 filter 注入格式一致。 */
    public static String authority(McpTokenScope scope) {
        return "SCOPE_" + scope.name();
    }

    public void require(McpTokenScope scope) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !hasScope(auth, scope)) {
            throw new McpScopeDeniedException(scope);
        }
    }

    private static boolean hasScope(Authentication auth, McpTokenScope scope) {
        String required = authority(scope);
        for (GrantedAuthority a : auth.getAuthorities()) {
            if (required.equals(a.getAuthority())) {
                return true;
            }
        }
        return false;
    }
}
