package com.kwikquant.account.infrastructure;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.kwikquant.AbstractIntegrationTest;
import com.kwikquant.KwikquantApplication;
import com.kwikquant.shared.infra.ErrorCode;
import com.kwikquant.shared.infra.WorkerTokenFilter;
import com.kwikquant.shared.infra.WorkerTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * WorkerTokenFilter 在真实 SecurityFilterChain 中的装配级验证。单元测试与 MockMvc 变体
 * 都直接驱动 filter 本体,抓不住"SecurityConfig 的 addFilterBefore 被移除/顺序被调乱"
 * 这类装配回归,本测试走完整链:
 *
 * <ul>
 *   <li>无效 token → 401 体是 filter 写的 7301——证明 filter 在链上且先于 JWT 入口
 *       (未装配时会落到 entry point 的 1001);</li>
 *   <li>无 token → JWT 入口的 1001——证明 JWT 用户请求不被 worker filter 劫持;</li>
 *   <li>有效 RUNNER token → 穿全链到达业务层(400/4103 账户不存在,而非 401)——
 *       证明 filter 注入的 SecurityContext 身份穿过了 authorization 层。</li>
 * </ul>
 */
@SpringBootTest(classes = KwikquantApplication.class)
@TestPropertySource(
        properties = {
            "JWT_SECRET=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
            "ENCRYPTION_KEY=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
        })
class WorkerTokenSecurityChainIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    WebApplicationContext context;

    @Autowired
    WorkerTokenService workerTokenService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUpMockMvc() {
        // 不用 @AutoConfigureMockMvc(Boot 4.1 已挪进未引入的模块化 test jar),
        // 手动 apply(springSecurity()):从 context 取 springSecurityFilterChain
        // (= SecurityConfig 装配的 FilterChainProxy,含 WorkerTokenFilter 的顺序),装配验证强度等价
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }

    @Test
    void invalidWorkerToken_gets7301FromFilter_notJwtEntrypoint() throws Exception {
        mockMvc.perform(get("/api/v1/positions").header(WorkerTokenFilter.TOKEN_HEADER, "bogus-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.WORKER_TOKEN_INVALID));
    }

    @Test
    void noToken_fallsThroughToJwtChain_getsUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/v1/positions"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHENTICATED));
    }

    @Test
    void validRunnerToken_passesWholeChain_reachesBusinessLayer() throws Exception {
        // token 绑不存在账户:filter 放行并注入身份后,PositionController 归属校验失败 → 400/4103。
        // 关键断言是"不再 401"——若 filter 未装配或身份注入失效,请求会在授权层被 1001 拦下。
        // message 断言钉住走的是 worker 归属分支(attrs 注入齐全):attrs 全丢会落
        // "accountId required for user requests" 分支,同为 400/4103,状态码断言无区分度。
        String token = workerTokenService.issueRunnerToken(7L, 1L, "OKX", 999_999_999L);
        mockMvc.perform(get("/api/v1/positions").header(WorkerTokenFilter.TOKEN_HEADER, token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.ORDER_INVALID_PARAMS))
                .andExpect(jsonPath("$.message").value(containsString("worker account not owned")));
    }
}
