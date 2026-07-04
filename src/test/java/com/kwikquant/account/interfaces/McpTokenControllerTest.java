package com.kwikquant.account.interfaces;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.kwikquant.shared.infra.DuplicateMcpTokenException;
import com.kwikquant.shared.infra.GlobalExceptionHandler;
import com.kwikquant.shared.infra.McpTokenService;
import com.kwikquant.shared.types.McpTokenIssueResult;
import com.kwikquant.shared.types.McpTokenView;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

/**
 * {@link McpTokenController} MockMvc 测试（standalone，JWT 鉴权通过预设 SecurityContext 模拟）。
 * 覆盖 issue/list/revoke/同名 3001 VALIDATION_FAILED。
 */
class McpTokenControllerTest {

    private MockMvc mockMvc;
    private McpTokenService tokenService;

    @BeforeEach
    void setUp() {
        tokenService = mock(McpTokenService.class);
        var controller = new McpTokenController(tokenService);
        var validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        // SecurityUtils.currentUserId() 需要认证上下文（与 MarketDataControllerTest 一致）
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken("123", null, List.of()));
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @Test
    void issue_validName_returnsToken() throws Exception {
        when(tokenService.issue(eq(123L), eq("claude-desktop")))
                .thenReturn(new McpTokenIssueResult(1L, "kq_pat_abc", "claude-desktop", Instant.now()));

        mockMvc.perform(post("/api/v1/mcp/tokens")
                        .contentType("application/json")
                        .content("{\"name\":\"claude-desktop\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.token").value("kq_pat_abc"))
                .andExpect(jsonPath("$.data.name").value("claude-desktop"));
    }

    @Test
    void issue_blankName_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/mcp/tokens")
                        .contentType("application/json")
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(3001));
    }

    @Test
    void issue_nameWithDot_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/mcp/tokens")
                        .contentType("application/json")
                        .content("{\"name\":\"bad.name\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(3001));
    }

    @Test
    void issue_nameOver64Chars_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/mcp/tokens")
                        .contentType("application/json")
                        .content("{\"name\":\"" + "a".repeat(65) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(3001));
    }

    @Test
    void issue_duplicateName_returns3001() throws Exception {
        when(tokenService.issue(anyLong(), eq("dup"))).thenThrow(new DuplicateMcpTokenException("dup"));

        mockMvc.perform(post("/api/v1/mcp/tokens")
                        .contentType("application/json")
                        .content("{\"name\":\"dup\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(3001));
    }

    @Test
    void list_returnsViews() throws Exception {
        when(tokenService.listByUser(123L))
                .thenReturn(List.of(
                        new McpTokenView(1L, "a", Instant.now(), null, null, null),
                        new McpTokenView(2L, "b", Instant.now(), null, null, null)));

        mockMvc.perform(get("/api/v1/mcp/tokens"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].name").value("a"))
                .andExpect(jsonPath("$.data[1].name").value("b"));
    }

    @Test
    void revoke_returns200() throws Exception {
        mockMvc.perform(delete("/api/v1/mcp/tokens/5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        verify(tokenService).revoke(5L, 123L);
    }
}
