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
    void backtestToken_onRemovedOrdersEndpoint_passesThroughToJwtFilter() throws Exception {
        // 撮合本地化删除 /backtests/{taskId}/orders:该路径不再是 Worker 端点,
        // 携 token 也放行给后续 filter chain(最终由 Spring Security/JWT 链处置)。
        String token = tokenService.issueBacktestToken(7L, 42L, 1L, "BINANCE");
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/backtests/42/orders");
        req.addHeader("X-Worker-Token", token);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        boolean[] chainCalled = new boolean[1];

        filter.doFilter(req, resp, (r, s) -> chainCalled[0] = true);

        assertThat(chainCalled[0]).isTrue();
        assertThat(resp.getStatus()).isEqualTo(200);
        assertThat(req.getAttribute(WorkerTokenFilter.WORKER_STRATEGY_ID_ATTR)).isNull();
    }

    @Test
    void backtestToken_onKlinesEndpoint_passesAndSetsStrategyId() throws Exception {
        // Worker 拉 K 线走 /api/v1/backtests/{taskId}/klines,同 BACKTEST token 放行 + 注入 strategyId
        String token = tokenService.issueBacktestToken(7L, 42L, 1L, "OKX");
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
    void backtestToken_onProgressEndpoint_passesAndSetsStrategyId() throws Exception {
        // 逐 bar 进度上报走 /api/v1/backtests/{taskId}/progress,同 BACKTEST token 放行 + 注入 strategyId
        String token = tokenService.issueBacktestToken(7L, 42L, 1L, "OKX");
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/backtests/42/progress");
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
        String token = tokenService.issueRunnerToken(7L, 1L, "BINANCE", 0L);
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
        // 无 X-Worker-Token header 的请求放行给后续 filter chain（JwtAuthenticationFilter），
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
        String token = tokenService.issueBacktestToken(7L, 42L, 1L, "BINANCE");
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
        String token = tokenService.issueRunnerToken(7L, 1L, "BINANCE", 0L);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/backtests/42/klines");
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
        // 验证 filter 放行时 SecurityContextHolder 含 Authentication(principal=userId)
        String token = tokenService.issueRunnerToken(7L, 42L, "BINANCE", 0L);
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
        // filter 结束必须 clearContext,防 Tomcat ThreadLocal 泄漏
        String token = tokenService.issueRunnerToken(9L, 100L, "BINANCE", 0L);
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
        String token = tokenService.issueBacktestToken(11L, 1L, 200L, "OKX");
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/backtests/1/progress");
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

    @Test
    void runnerToken_onPositionsEndpoint_passesAndSetsStrategyId() throws Exception {
        // Runner 查持仓 /api/v1/positions,RUNNER token 放行 + 注入 strategyId(后端推导 account)
        String token = tokenService.issueRunnerToken(7L, 1L, "OKX", 0L);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/positions");
        req.addHeader("X-Worker-Token", token);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        boolean[] chainCalled = new boolean[1];

        filter.doFilter(req, resp, (r, s) -> chainCalled[0] = true);

        assertThat(chainCalled[0]).isTrue();
        assertThat(resp.getStatus()).isEqualTo(200);
        assertThat(req.getAttribute(WorkerTokenFilter.WORKER_STRATEGY_ID_ATTR)).isEqualTo(7L);
    }

    @Test
    void runnerToken_onSubscribeKlineEndpoint_passes() throws Exception {
        // Runner 启动 REST /market/subscribe/kline 触发 persistent kline 订阅,RUNNER token 放行
        String token = tokenService.issueRunnerToken(7L, 1L, "OKX", 0L);
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/market/subscribe/kline");
        req.addHeader("X-Worker-Token", token);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        boolean[] chainCalled = new boolean[1];

        filter.doFilter(req, resp, (r, s) -> chainCalled[0] = true);

        assertThat(chainCalled[0]).isTrue();
        assertThat(resp.getStatus()).isEqualTo(200);
    }

    @Test
    void runnerToken_onMarketKlinesEndpoint_passesAndSetsStrategyId() throws Exception {
        // Runner 重启后预填历史 bar:GET /api/v1/market/klines,RUNNER token 放行 + 注入 strategyId。
        // tokenMatchesEndpoint 对 RUNNER 返 !isBacktestEndpoint → /market/klines 自动放行。
        String token = tokenService.issueRunnerToken(7L, 1L, "OKX", 0L);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/market/klines");
        req.addHeader("X-Worker-Token", token);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        boolean[] chainCalled = new boolean[1];

        filter.doFilter(req, resp, (r, s) -> chainCalled[0] = true);

        assertThat(chainCalled[0]).isTrue();
        assertThat(resp.getStatus()).isEqualTo(200);
        assertThat(req.getAttribute(WorkerTokenFilter.WORKER_STRATEGY_ID_ATTR)).isEqualTo(7L);
    }

    @Test
    void backtestToken_onMarketKlinesEndpoint_returns401_taskTypeMismatch() throws Exception {
        // BACKTEST token 不能调 /market/klines(回测拉数据走 task-scoped /api/v1/backtests/{taskId}/klines)。
        // tokenMatchesEndpoint 对 BACKTEST 要求 isBacktestEndpoint → /market/klines 不匹配 → 401。
        String token = tokenService.issueBacktestToken(7L, 42L, 1L, "OKX");
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/market/klines");
        req.addHeader("X-Worker-Token", token);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        boolean[] chainCalled = new boolean[1];

        filter.doFilter(req, resp, (r, s) -> chainCalled[0] = true);

        assertThat(chainCalled[0]).isFalse();
        assertThat(resp.getStatus()).isEqualTo(401);
    }

    @Test
    void missingToken_onMarketKlinesEndpoint_passesThroughToJwtFilter() throws Exception {
        // JWT 用户(前端拉 K 线画图)走 /api/v1/market/klines 无 X-Worker-Token → 放行给 JwtAuthenticationFilter,
        // 不被 WorkerTokenFilter 拦截(与 /api/v1/orders 无 token 放行一致)。
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/market/klines");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        boolean[] chainCalled = new boolean[1];

        filter.doFilter(req, resp, (r, s) -> chainCalled[0] = true);

        assertThat(chainCalled[0]).isTrue();
        assertThat(resp.getStatus()).isEqualTo(200);
    }

    @Test
    void backtestToken_onPositionsEndpoint_returns401_taskTypeMismatch() throws Exception {
        // BACKTEST token 不能调 /positions(RUNNER 端点),taskType 不匹配 → 401
        String token = tokenService.issueBacktestToken(7L, 42L, 1L, "OKX");
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/positions");
        req.addHeader("X-Worker-Token", token);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        boolean[] chainCalled = new boolean[1];

        filter.doFilter(req, resp, (r, s) -> chainCalled[0] = true);

        assertThat(chainCalled[0]).isFalse();
        assertThat(resp.getStatus()).isEqualTo(401);
    }

    @Test
    void runnerToken_onOrderCancelEndpoint_passes() throws Exception {
        // Runner 撤单 DELETE /api/v1/orders/{id}(被动限价策略每 bar 撤挂单),RUNNER token 放行;
        // 归属由下游 TradingService.cancel 的 getOwned 校验兜底
        String token = tokenService.issueRunnerToken(7L, 1L, "OKX", 0L);
        MockHttpServletRequest req = new MockHttpServletRequest("DELETE", "/api/v1/orders/123");
        req.addHeader("X-Worker-Token", token);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        boolean[] chainCalled = new boolean[1];

        filter.doFilter(req, resp, (r, s) -> chainCalled[0] = true);

        assertThat(chainCalled[0]).isTrue();
        assertThat(req.getAttribute(WorkerTokenFilter.WORKER_STRATEGY_ID_ATTR)).isEqualTo(7L);
    }

    @Test
    void runnerToken_onMarketKlinesEndpoint_passes() throws Exception {
        // Runner 启动 warmup 拉历史 K 线 GET /api/v1/market/klines(只读行情),RUNNER token 放行
        String token = tokenService.issueRunnerToken(7L, 1L, "OKX", 0L);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/market/klines");
        req.addHeader("X-Worker-Token", token);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        boolean[] chainCalled = new boolean[1];

        filter.doFilter(req, resp, (r, s) -> chainCalled[0] = true);

        assertThat(chainCalled[0]).isTrue();
        assertThat(resp.getStatus()).isEqualTo(200);
    }

    @Test
    void backtestToken_onDifferentTask_returns401() throws Exception {
        String token = tokenService.issueBacktestToken(7L, 41L, 1L, "BINANCE");
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/backtests/42/klines");
        req.addHeader("X-Worker-Token", token);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        boolean[] chainCalled = new boolean[1];

        filter.doFilter(req, resp, (r, s) -> chainCalled[0] = true);

        assertThat(chainCalled[0]).isFalse();
        assertThat(resp.getStatus()).isEqualTo(401);
    }

    @Test
    void runnerToken_onBootstrapEndpoint_passesAndSetsStrategyId() throws Exception {
        // Runner 拉取式 bootstrap(③):GET /api/v1/worker/bootstrap,RUNNER token 放行 + 注入 strategyId。
        // tokenMatchesEndpoint 对 RUNNER 返 !isBacktestEndpoint → /worker/bootstrap 自动放行。
        String token = tokenService.issueRunnerToken(7L, 1L, "OKX", 0L);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/worker/bootstrap");
        req.addHeader("X-Worker-Token", token);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        boolean[] chainCalled = new boolean[1];

        filter.doFilter(req, resp, (r, s) -> chainCalled[0] = true);

        assertThat(chainCalled[0]).isTrue();
        assertThat(resp.getStatus()).isEqualTo(200);
        assertThat(req.getAttribute(WorkerTokenFilter.WORKER_STRATEGY_ID_ATTR)).isEqualTo(7L);
    }

    @Test
    void backtestToken_onBootstrapEndpoint_returns401_taskTypeMismatch() throws Exception {
        // BACKTEST token 不能调 /worker/bootstrap(回测走 stdin 下发,非 bootstrap)。
        // tokenMatchesEndpoint 对 BACKTEST 要求 isBacktestEndpoint → /worker/bootstrap 不匹配 → 401。
        String token = tokenService.issueBacktestToken(7L, 42L, 1L, "OKX");
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/worker/bootstrap");
        req.addHeader("X-Worker-Token", token);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        boolean[] chainCalled = new boolean[1];

        filter.doFilter(req, resp, (r, s) -> chainCalled[0] = true);

        assertThat(chainCalled[0]).isFalse();
        assertThat(resp.getStatus()).isEqualTo(401);
    }
}
