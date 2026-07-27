package com.kwikquant.account.interfaces;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.kwikquant.account.application.LlmApiKeyService;
import com.kwikquant.account.application.LlmApiKeyService.LlmApiKeyView;
import com.kwikquant.account.domain.LlmApiKey;
import com.kwikquant.shared.infra.GlobalExceptionHandler;
import com.kwikquant.shared.types.LlmProvider;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

/**
 * {@link LlmApiKeyController} MockMvc 测试(standalone,参照 {@code AiChatControllerTest} 模式)。
 *
 * <p>覆盖 create/list/delete 端点。原有 {@code LlmApiKeyControllerTest} 只测 CreateLlmKeyRequest 的 label
 * {@code @Pattern} Bean Validation(不调 controller 方法),致 Jacoco 14%;此 test 调真 controller 方法补覆盖。
 */
class LlmApiKeyControllerMockMvcTest {

    private MockMvc mockMvc;
    private LlmApiKeyService keyService;

    @BeforeEach
    void setUp() {
        keyService = mock(LlmApiKeyService.class);
        var controller = new LlmApiKeyController(keyService);
        var validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        // SecurityUtils.currentUserId() 需认证上下文(与 AiChatControllerTest/McpTokenControllerTest 一致)
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken("42", null, List.of()));
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void create_returnsView() throws Exception {
        LlmApiKey entity = new LlmApiKey();
        entity.setId(1L);
        when(keyService.create(eq(42L), eq("label"), eq(LlmProvider.OPENAI), any(), any(), any()))
                .thenReturn(entity);
        when(keyService.view(entity))
                .thenReturn(new LlmApiKeyView(1L, "label", LlmProvider.OPENAI, "sk...3456", "", "", Instant.now()));
        mockMvc.perform(
                        post("/api/v1/ai/keys")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"label\":\"label\",\"provider\":\"OPENAI\",\"apiKey\":\"sk-abc\",\"baseUrl\":\"\",\"model\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.label").value("label"));
    }

    @Test
    void create_openAiCompatibleWithBaseUrlAndModel_returnsView() throws Exception {
        LlmApiKey entity = new LlmApiKey();
        entity.setId(2L);
        when(keyService.create(eq(42L), eq("compat"), eq(LlmProvider.OPENAI_COMPATIBLE), any(), any(), any()))
                .thenReturn(entity);
        when(keyService.view(entity))
                .thenReturn(new LlmApiKeyView(
                        2L,
                        "compat",
                        LlmProvider.OPENAI_COMPATIBLE,
                        "sk...1234",
                        "https://api.deepseek.com/v1",
                        "deepseek-chat",
                        Instant.now()));
        mockMvc.perform(
                        post("/api/v1/ai/keys")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"label\":\"compat\",\"provider\":\"OPENAI_COMPATIBLE\",\"apiKey\":\"sk-x123\",\"baseUrl\":\"https://api.deepseek.com/v1\",\"model\":\"deepseek-chat\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.model").value("deepseek-chat"));
    }

    @Test
    void list_returnsViews() throws Exception {
        when(keyService.listByUser(42L))
                .thenReturn(List.of(
                        new LlmApiKeyView(1L, "label", LlmProvider.OPENAI, "sk...3456", "", "", Instant.now())));
        mockMvc.perform(get("/api/v1/ai/keys"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].label").value("label"));
    }

    @Test
    void delete_removesKey() throws Exception {
        mockMvc.perform(delete("/api/v1/ai/keys/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        verify(keyService).delete(1L, 42L);
    }

    @Test
    void create_blankLabel_rejected400() throws Exception {
        // @Valid @NotBlank label 拦截,不调 service
        mockMvc.perform(post("/api/v1/ai/keys")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"\",\"provider\":\"OPENAI\",\"apiKey\":\"sk-abc\"}"))
                .andExpect(status().isBadRequest());
        verify(keyService, never()).create(anyLong(), any(), any(), any(), any(), any());
    }
}
