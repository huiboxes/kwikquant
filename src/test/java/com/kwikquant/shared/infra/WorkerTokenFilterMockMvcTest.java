package com.kwikquant.shared.infra;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@link WorkerTokenFilter} 的 MockMvc 变体:把 filter 挂进真实 servlet 管道驱动,
 * 补充 {@link WorkerTokenFilterTest} 的直接调用形态——验证 401 响应体确为
 * {@link JsonErrorWriter} 写出的 ApiResponse 信封(code=7301,直调形态只断言了状态码)、
 * 放行注入的 request attr 随请求传播到 handler、handler 执行期能读到 SecurityContext 身份。
 */
class WorkerTokenFilterMockMvcTest {

    private WorkerTokenService tokenService;
    private MockMvc mockMvc;

    /** 探针 controller:把 filter 注入的 attr 与 SecurityContext 身份渲染为易断言文本。 */
    @RestController
    static class ProbeController {

        @GetMapping("/api/v1/positions")
        String positions(HttpServletRequest req) {
            return probe(req);
        }

        @GetMapping("/api/v1/backtests/{taskId}/klines")
        String klines(HttpServletRequest req) {
            return probe(req);
        }

        private String probe(HttpServletRequest req) {
            var auth = SecurityContextHolder.getContext().getAuthentication();
            return "strategyId=" + req.getAttribute(WorkerTokenFilter.WORKER_STRATEGY_ID_ATTR)
                    + ";accountId=" + req.getAttribute(WorkerTokenFilter.WORKER_ACCOUNT_ID_ATTR)
                    + ";principal=" + (auth == null ? "none" : auth.getName());
        }
    }

    @BeforeEach
    void setUp() {
        tokenService = new WorkerTokenService();
        mockMvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .addFilters(new WorkerTokenFilter(tokenService))
                .build();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void runnerToken_reachesHandler_withAttrsAndPrincipal() throws Exception {
        String token = tokenService.issueRunnerToken(7L, 42L, "OKX", 55L);
        mockMvc.perform(get("/api/v1/positions").header(WorkerTokenFilter.TOKEN_HEADER, token))
                .andExpect(status().isOk())
                .andExpect(content().string("strategyId=7;accountId=55;principal=42"));
    }

    @Test
    void backtestToken_onOwnTaskKlines_reachesHandlerWithStrategyId() throws Exception {
        // BACKTEST token 打自己任务的 klines:放行 + strategyId attr 到 handler;
        // accountId 是 RUNNER 专属绑定(BACKTEST 恒为哨兵 0=不绑定,回测账本在 worker 本地),principal=userId
        String token = tokenService.issueBacktestToken(7L, 42L, 1L, "OKX");
        mockMvc.perform(get("/api/v1/backtests/42/klines").header(WorkerTokenFilter.TOKEN_HEADER, token))
                .andExpect(status().isOk())
                .andExpect(content().string("strategyId=7;accountId=0;principal=1"));
    }

    @Test
    void invalidToken_401WithApiResponseEnvelope() throws Exception {
        mockMvc.perform(get("/api/v1/positions").header(WorkerTokenFilter.TOKEN_HEADER, "bogus-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.WORKER_TOKEN_INVALID))
                .andExpect(jsonPath("$.message").value("worker token invalid or endpoint mismatch"));
    }

    @Test
    void backtestToken_onRunnerEndpoint_401WithEnvelope() throws Exception {
        String token = tokenService.issueBacktestToken(7L, 42L, 1L, "OKX");
        mockMvc.perform(get("/api/v1/positions").header(WorkerTokenFilter.TOKEN_HEADER, token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.WORKER_TOKEN_INVALID));
    }

    @Test
    void noToken_passesThroughToHandler_withoutIdentity() throws Exception {
        // JWT 用户路径:无 X-Worker-Token 的请求被 filter 放行(不注入 attr、不注入身份),
        // 由后续安全链接管(standalone 无 Security 链,直达 handler)
        mockMvc.perform(get("/api/v1/positions"))
                .andExpect(status().isOk())
                .andExpect(content().string("strategyId=null;accountId=null;principal=none"));
    }
}
