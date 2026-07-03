package com.kwikquant.shared.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

class McpTokenAuthenticationFilterTest {

    private static final String VALID_PAT = "kq_pat_abcdef0123456789abcdef0123456789";

    private McpTokenService tokenService;
    private McpTokenAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        tokenService = mock(McpTokenService.class);
        filter = new McpTokenAuthenticationFilter(tokenService);
    }

    @BeforeEach
    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void validToken_onMcpEndpoint_setsSecurityContextAndUpdatesLastUsedAt() throws Exception {
        when(tokenService.verify(VALID_PAT)).thenReturn(42L);
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/mcp");
        req.addHeader("Authorization", "Bearer " + VALID_PAT);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        String[] observedPrincipal = new String[1];

        filter.doFilter(req, resp, (r, s) -> {
            var auth = SecurityContextHolder.getContext().getAuthentication();
            observedPrincipal[0] = auth == null ? null : auth.getName();
        });

        assertThat(resp.getStatus()).isEqualTo(200);
        assertThat(observedPrincipal[0]).isEqualTo("42");
        // verify 被调，触发 last_used_at 更新（Service 内部）
        verify(tokenService).verify(VALID_PAT);
    }

    @Test
    void missingBearer_onMcpEndpoint_returns401With10001() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/mcp");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        boolean[] chainCalled = new boolean[1];

        filter.doFilter(req, resp, (r, s) -> chainCalled[0] = true);

        assertThat(chainCalled[0]).isFalse();
        assertThat(resp.getStatus()).isEqualTo(401);
        assertThat(resp.getContentAsString()).contains("10001");
        verify(tokenService, never()).verify(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void invalidToken_onMcpEndpoint_returns401With10001() throws Exception {
        when(tokenService.verify("kq_pat_bogus")).thenReturn(null);
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/mcp");
        req.addHeader("Authorization", "Bearer kq_pat_bogus");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        boolean[] chainCalled = new boolean[1];

        filter.doFilter(req, resp, (r, s) -> chainCalled[0] = true);

        assertThat(chainCalled[0]).isFalse();
        assertThat(resp.getStatus()).isEqualTo(401);
        assertThat(resp.getContentAsString()).contains("10001");
    }

    @Test
    void nonMcpEndpoint_passesThroughWithoutTokenCheck() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/orders");
        // 不带 Authorization，PAT filter 应直通，不抢 /api/v1（JwtFilter 接管）
        MockHttpServletResponse resp = new MockHttpServletResponse();
        boolean[] chainCalled = new boolean[1];

        filter.doFilter(req, resp, (r, s) -> chainCalled[0] = true);

        assertThat(chainCalled[0]).isTrue();
        assertThat(resp.getStatus()).isEqualTo(200);
        verify(tokenService, never()).verify(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void nonMcpEndpoint_doesNotSetSecurityContext() throws Exception {
        // 非 /mcp 路径直通，PAT filter 不应注入身份
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/auth/login");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, (r, s) -> {});

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void validToken_clearsSecurityContextAfterChain() throws Exception {
        // 防 Tomcat 线程池 ThreadLocal 跨用户身份漂移（复刻 WorkerTokenFilter Round-7 修复）
        when(tokenService.verify(VALID_PAT)).thenReturn(100L);
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/mcp");
        req.addHeader("Authorization", "Bearer " + VALID_PAT);
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, (r, s) -> {
            // chain 中 context 应有 auth，已由 validToken_onMcpEndpoint 覆盖
        });

        assertThat(SecurityContextHolder.getContext().getAuthentication())
                .as("filter 结束后 SecurityContextHolder 必须已清理，防线程池泄漏")
                .isNull();
    }

    @Test
    void chainException_stillClearsSecurityContext() throws Exception {
        // 深度防御：即使 downstream chain 抛异常，finally 保证 clearContext
        when(tokenService.verify(VALID_PAT)).thenReturn(200L);
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/mcp");
        req.addHeader("Authorization", "Bearer " + VALID_PAT);
        MockHttpServletResponse resp = new MockHttpServletResponse();

        try {
            filter.doFilter(req, resp, (r, s) -> {
                throw new RuntimeException("downstream boom");
            });
        } catch (Exception ignored) {
            // 预期异常
        }
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void mcpSubPath_matchedAndAuthenticated() throws Exception {
        // /mcp/** 子路径也应被 PAT filter 接管
        when(tokenService.verify(VALID_PAT)).thenReturn(7L);
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/mcp/sse");
        req.addHeader("Authorization", "Bearer " + VALID_PAT);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        boolean[] chainCalled = new boolean[1];

        filter.doFilter(req, resp, (r, s) -> chainCalled[0] = true);

        assertThat(chainCalled[0]).isTrue();
        assertThat(resp.getStatus()).isEqualTo(200);
    }

    @Test
    void emptyBearerToken_returns401() throws Exception {
        // Authorization: Bearer （空 token）→ 401，不调 verify
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/mcp");
        req.addHeader("Authorization", "Bearer ");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        boolean[] chainCalled = new boolean[1];

        filter.doFilter(req, resp, (r, s) -> chainCalled[0] = true);

        assertThat(chainCalled[0]).isFalse();
        assertThat(resp.getStatus()).isEqualTo(401);
        verify(tokenService, never()).verify(org.mockito.ArgumentMatchers.anyString());
    }
}
