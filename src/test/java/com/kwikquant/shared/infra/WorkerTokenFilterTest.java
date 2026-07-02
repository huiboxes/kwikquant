package com.kwikquant.shared.infra;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class WorkerTokenFilterTest {

    private final WorkerTokenService tokenService = new WorkerTokenService();
    private final WorkerTokenFilter filter = new WorkerTokenFilter(tokenService);

    @Test
    void backtestToken_onBacktestEndpoint_passesAndSetsStrategyId() throws Exception {
        String token = tokenService.issueToken(7L, "BACKTEST");
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
    void runnerToken_onOrdersEndpoint_passesAndSetsStrategyId() throws Exception {
        String token = tokenService.issueToken(7L, "RUNNER");
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/orders");
        req.addHeader("X-Worker-Token", token);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        boolean[] chainCalled = new boolean[1];

        filter.doFilter(req, resp, (r, s) -> chainCalled[0] = true);

        assertThat(chainCalled[0]).isTrue();
        assertThat(req.getAttribute(WorkerTokenFilter.WORKER_STRATEGY_ID_ATTR)).isEqualTo(7L);
    }

    @Test
    void missingToken_onWorkerEndpoint_returns401() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/orders");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        boolean[] chainCalled = new boolean[1];

        filter.doFilter(req, resp, (r, s) -> chainCalled[0] = true);

        assertThat(chainCalled[0]).isFalse();
        assertThat(resp.getStatus()).isEqualTo(401);
        assertThat(resp.getContentAsString()).contains("7301");
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
        String token = tokenService.issueToken(7L, "BACKTEST");
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
        String token = tokenService.issueToken(7L, "RUNNER");
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
}
