package com.kwikquant.mcp.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kwikquant.shared.infra.McpScopeDeniedException;
import com.kwikquant.shared.types.McpTokenScope;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/** {@link McpScopeGuard} 单测:fail-closed + authority 匹配。 */
class McpScopeGuardTest {

    private final McpScopeGuard guard = new McpScopeGuard();

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void auth(String... scopes) {
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(
                        "42",
                        "x",
                        List.of(scopes).stream()
                                .map(SimpleGrantedAuthority::new)
                                .toList()));
    }

    @Test
    void require_matchingScope_passes() {
        auth("SCOPE_TRADE", "SCOPE_READ");
        assertThatCode(() -> guard.require(McpTokenScope.TRADE)).doesNotThrowAnyException();
    }

    @Test
    void require_missingScope_throws10005() {
        auth("SCOPE_READ");
        assertThatThrownBy(() -> guard.require(McpTokenScope.TRADE)).isInstanceOf(McpScopeDeniedException.class);
    }

    @Test
    void require_noAuth_failClosed() {
        SecurityContextHolder.clearContext();
        assertThatThrownBy(() -> guard.require(McpTokenScope.READ)).isInstanceOf(McpScopeDeniedException.class);
    }

    @Test
    void require_emptyAuthorities_failClosed() {
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken("42", "x", List.of()));
        assertThatThrownBy(() -> guard.require(McpTokenScope.READ)).isInstanceOf(McpScopeDeniedException.class);
    }
}
