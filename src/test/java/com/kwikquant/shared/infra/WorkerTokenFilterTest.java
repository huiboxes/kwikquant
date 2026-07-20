package com.kwikquant.shared.infra;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

class WorkerTokenFilterTest {

    private final WorkerTokenService tokenService = new WorkerTokenService();
    private final WorkerTokenFilter filter = new WorkerTokenFilter(tokenService);

    @BeforeEach
    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void backtestToken_onBacktestEndpoint_passesAndSetsStrategyId() throws Exception {
        String token = tokenService.issueToken(7L, "BACKTEST", 1L, "BINANCE");
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/backtests/42/orders");
        req.addHeader("X-Worker-Token", token);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        boolean[] chainCalled = new boolean[1];

        filter.doFilter(req, resp, (r, s) -> chainCalled[0] = true);

        assertThat(chainCalled[0]).isTrue();
        assertThat(resp.getStatus()).isEqualTo(200);
        assertThat(req.getAttribute(WorkerTokenFilter.WORKER_STRATEGY_ID_ATTR)).isEqualTo(7L);
    }

    @Test
    void backtestToken_onKlinesEndpoint_passesAndSetsStrategyId() throws Exception {
        // Task 4: Worker 拉 K 线走 /api/v1/backtests/{taskId}/klines,同 BACKTEST token 放行 + 注入 strategyId
        String token = tokenService.issueToken(7L, "BACKTEST", 1L, "OKX");
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/backtests/42/klines");
        req.addHeader("X-Worker-Token", token);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        boolean[] chainCalled = new boolean[1];

        filter.doFilter(req, resp, (r, s) -> chainCalled[0] = true);

        assertThat(chainCalled[0]).isTrue();
        assertThat(resp.getStatus()).isEqualTo(200);
        assertThat(req.getAttribute(WorkerTokenFilter.WORKER_STRATEGY_ID_ATTR)).isEqualTo(7L);
    }

    @Test
    void runnerToken_onOrdersEndpoint_passesAndSetsStrategyId() throws Exception {
        String token = tokenService.issueToken(7L, "RUNNER", 1L, "BINANCE");
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/orders");
        req.addHeader("X-Worker-Token", token);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        boolean[] chainCalled = new boolean[1];

        filter.doFilter(req, resp, (r, s) -> chainCalled[0] = true);

        assertThat(chainCalled[0]).isTrue();
        assertThat(req.getAttribute(WorkerTokenFilter.WORKER_STRATEGY_ID_ATTR)).isEqualTo(7L);
    }

    @Test
    void missingToken_onWorkerEndpoint_passesThroughToJwtFilter() throws Exception {
        // TD-030 fix: 无 X-Worker-Token header 的请求放行给后续 filter chain（JwtAuthenticationFilter），
        // 不再被 WorkerTokenFilter 拦截返回 401。JWT 用户通过 /api/v1/orders 下单依赖此行为。
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/orders");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        boolean[] chainCalled = new boolean[1];

        filter.doFilter(req, resp, (r, s) -> chainCalled[0] = true);

        assertThat(chainCalled[0]).isTrue();
        assertThat(resp.getStatus()).isEqualTo(200);
    }

    @Test
    void invalidToken_returns401() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/orders");
        req.addHeader("X-Worker-Token", "bogus-token");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        boolean[] chainCalled = new boolean[1];

        filter.doFilter(req, resp, (r, s) -> chainCalled[0] = true);

        assertThat(chainCalled[0]).isFalse();
        assertThat(resp.getStatus()).isEqualTo(401);
    }

    @Test
    void backtestToken_onOrdersEndpoint_returns401_taskTypeMismatch() throws Exception {
        String token = tokenService.issueToken(7L, "BACKTEST", 1L, "BINANCE");
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/orders");
        req.addHeader("X-Worker-Token", token);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        boolean[] chainCalled = new boolean[1];

        filter.doFilter(req, resp, (r, s) -> chainCalled[0] = true);

        assertThat(chainCalled[0]).isFalse();
        assertThat(resp.getStatus()).isEqualTo(401);
    }

    @Test
    void runnerToken_onBacktestEndpoint_returns401_taskTypeMismatch() throws Exception {
        String token = tokenService.issueToken(7L, "RUNNER", 1L, "BINANCE");
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/backtests/42/orders");
        req.addHeader("X-Worker-Token", token);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        boolean[] chainCalled = new boolean[1];

        filter.doFilter(req, resp, (r, s) -> chainCalled[0] = true);

        assertThat(chainCalled[0]).isFalse();
        assertThat(resp.getStatus()).isEqualTo(401);
    }

    @Test
    void nonWorkerEndpoint_passesThroughWithoutTokenCheck() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        boolean[] chainCalled = new boolean[1];

        filter.doFilter(req, resp, (r, s) -> chainCalled[0] = true);

        assertThat(chainCalled[0]).isTrue();
        assertThat(resp.getStatus()).isEqualTo(200);
    }

    @Test
    void validToken_setsSecurityContextAuthenticationDuringChain() throws Exception {
        // Round-7 补测试:验证 filter 放行时 SecurityContextHolder 含 Authentication(principal=userId)
        String token = tokenService.issueToken(7L, "RUNNER", 42L, "BINANCE");
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/orders");
        req.addHeader("X-Worker-Token", token);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        String[] observedPrincipal = new String[1];

        filter.doFilter(req, resp, (r, s) -> {
            var auth = SecurityContextHolder.getContext().getAuthentication();
            observedPrincipal[0] = auth == null ? null : auth.getName();
        });

        assertThat(observedPrincipal[0]).isEqualTo("42");
    }

    @Test
    void validToken_clearsSecurityContextAfterChain() throws Exception {
        // Round-7 BLOCKER 2 修复:filter 结束必须 clearContext,防 Tomcat ThreadLocal 泄漏
        String token = tokenService.issueToken(9L, "RUNNER", 100L, "BINANCE");
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/orders");
        req.addHeader("X-Worker-Token", token);
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, (r, s) -> {
            // chain 中 context 应该有 auth,已由上一个 test 覆盖
        });

        assertThat(SecurityContextHolder.getContext().getAuthentication())
                .as("filter 结束后 SecurityContextHolder 必须已清理,防线程池泄漏")
                .isNull();
    }

    @Test
    void chainException_stillClearsSecurityContext() throws Exception {
        // 深度防御:即使 downstream chain 抛异常,finally 保证 clearContext
        String token = tokenService.issueToken(11L, "BACKTEST", 200L, "OKX");
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/backtests/1/orders");
        req.addHeader("X-Worker-Token", token);
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
}
