package com.kwikquant.strategy.interfaces;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.kwikquant.shared.infra.GlobalExceptionHandler;
import com.kwikquant.shared.infra.OwnershipViolationException;
import com.kwikquant.strategy.application.AiChatMessageService;
import com.kwikquant.strategy.application.AiChatRequest;
import com.kwikquant.strategy.application.AiChatService;
import com.kwikquant.strategy.domain.AiChatMessage;
import com.kwikquant.strategy.domain.StrategyNotFoundException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import reactor.core.publisher.Flux;

/**
 * {@link AiChatController} MockMvc 测试(standalone,JWT 鉴权通过预设 SecurityContext 模拟,
 * 参照 {@code McpTokenControllerTest} 模式)。
 *
 * <p>覆盖:
 * <ul>
 *   <li>GET /api/v1/strategies/{id}/ai/messages owner 返 List&lt;AiChatMessageView&gt;(happy path)</li>
 *   <li>GET 非 owner 403(OwnershipViolationException 经 GlobalExceptionHandler 映射)</li>
 *   <li>GET 策略不存在 404(StrategyNotFoundException 经 StrategyExceptionHandler 映射)</li>
 *   <li>DELETE /strategies/{id}/ai/messages owner 清空</li>
 *   <li>POST /strategies/{id}/ai/messages 保存 AI 回复</li>
 *   <li>POST /api/v1/ai/chat 进来先存 user 消息(最后一条 messages)再调 chat</li>
 *   <li>POST /api/v1/ai/chat strategyId=null 不存消息(无 strategyId 的会话不持久化)</li>
 * </ul>
 */
class AiChatControllerTest {

    private MockMvc mockMvc;
    private AiChatService aiChatService;
    private AiChatMessageService messageService;

    @BeforeEach
    void setUp() {
        aiChatService = mock(AiChatService.class);
        messageService = mock(AiChatMessageService.class);
        var controller = new AiChatController(aiChatService, messageService);
        var validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        // SecurityUtils.currentUserId() 需要认证上下文(与 McpTokenControllerTest 一致)
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken("42", null, List.of()));
        // StrategyExceptionHandler 处理 StrategyNotFoundException→7001/404;
        // GlobalExceptionHandler 处理 OwnershipViolationException→403
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new StrategyExceptionHandler(), new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @Test
    void getMessages_whenOwner_shouldReturnHistory() throws Exception {
        AiChatMessage m1 = new AiChatMessage();
        m1.setId(1L);
        m1.setUserId(42L);
        m1.setStrategyId(5L);
        m1.setRole("user");
        m1.setContent("帮我优化 MA");
        m1.setCreatedAt(Instant.parse("2026-07-28T10:00:00Z"));
        AiChatMessage m2 = new AiChatMessage();
        m2.setId(2L);
        m2.setUserId(42L);
        m2.setStrategyId(5L);
        m2.setRole("ai");
        m2.setContent("建议...");
        m2.setModel("gpt-4o");
        m2.setCreatedAt(Instant.parse("2026-07-28T10:00:01Z"));
        when(messageService.loadHistory(5L, 42L)).thenReturn(List.of(m1, m2));

        mockMvc.perform(get("/api/v1/strategies/5/ai/messages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].id").value(1))
                .andExpect(jsonPath("$.data[0].strategyId").value(5))
                .andExpect(jsonPath("$.data[0].role").value("user"))
                .andExpect(jsonPath("$.data[0].content").value("帮我优化 MA"))
                .andExpect(jsonPath("$.data[0].model").doesNotExist())
                .andExpect(jsonPath("$.data[1].role").value("ai"))
                .andExpect(jsonPath("$.data[1].model").value("gpt-4o"));
    }

    @Test
    void getMessages_whenNotOwner_should403() throws Exception {
        when(messageService.loadHistory(5L, 42L)).thenThrow(new OwnershipViolationException("strategy"));

        mockMvc.perform(get("/api/v1/strategies/5/ai/messages"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(1002));
    }

    @Test
    void getMessages_whenStrategyNotFound_should404() throws Exception {
        when(messageService.loadHistory(999L, 42L)).thenThrow(new StrategyNotFoundException(999L));

        mockMvc.perform(get("/api/v1/strategies/999/ai/messages"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(7001));
    }

    @Test
    void clearMessages_whenOwner_shouldReturn200() throws Exception {
        mockMvc.perform(delete("/api/v1/strategies/5/ai/messages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        verify(messageService).deleteAll(5L, 42L);
    }

    @Test
    void clearMessages_whenNotOwner_should403() throws Exception {
        doThrow(new OwnershipViolationException("strategy"))
                .when(messageService)
                .deleteAll(5L, 42L);

        mockMvc.perform(delete("/api/v1/strategies/5/ai/messages"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(1002));
    }

    @Test
    void saveAiMessage_whenOwner_shouldReturnView() throws Exception {
        AiChatMessage saved = new AiChatMessage();
        saved.setId(10L);
        saved.setUserId(42L);
        saved.setStrategyId(5L);
        saved.setRole("ai");
        saved.setContent("建议优化...");
        saved.setModel("gpt-4o");
        saved.setCreatedAt(Instant.parse("2026-07-28T11:00:00Z"));
        when(messageService.saveMessage(eq(5L), eq(42L), eq("ai"), eq("建议优化..."), eq("gpt-4o")))
                .thenReturn(saved);

        mockMvc.perform(post("/api/v1/strategies/5/ai/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"建议优化...\",\"model\":\"gpt-4o\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(10))
                .andExpect(jsonPath("$.data.role").value("ai"))
                .andExpect(jsonPath("$.data.content").value("建议优化..."))
                .andExpect(jsonPath("$.data.model").value("gpt-4o"));
    }

    @Test
    void saveAiMessage_whenBlankContent_should400() throws Exception {
        mockMvc.perform(post("/api/v1/strategies/5/ai/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(3001));
    }

    @Test
    void postChat_shouldSaveUserMessageBeforeStream() throws Exception {
        // happy path:POST /ai/chat 带 strategyId + messages,应先存最后一条 user 消息再返 Flux SSE
        when(aiChatService.chat(any(AiChatRequest.class), eq(42L)))
                .thenReturn(Flux.just(ServerSentEvent.<String>builder()
                        .event("message")
                        .data("hello")
                        .build()));

        // messages 最后一条是 user("optimize"),应被存(role=user, model=null)
        mockMvc.perform(
                        post("/api/v1/ai/chat")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"llmKeyId\":1,\"strategyId\":5,\"messages\":[{\"role\":\"user\",\"content\":\"optimize\"}]}"))
                .andExpect(status().isOk());

        // 验证 saveMessage 用正确参数被调(role=user, content=最后一条, model=null user 消息恒 null)
        verify(messageService).saveMessage(eq(5L), eq(42L), eq("user"), eq("optimize"), isNull());
        // 验证 aiChatService.chat 也被调(stream 阶段)
        verify(aiChatService).chat(any(AiChatRequest.class), eq(42L));
    }

    @Test
    void postChat_whenNoStrategyId_shouldNotSaveMessage() throws Exception {
        // strategyId=null 时不应存消息(无 strategyId 的会话不持久化)
        when(aiChatService.chat(any(AiChatRequest.class), eq(42L)))
                .thenReturn(Flux.just(ServerSentEvent.<String>builder()
                        .event("done")
                        .data("[DONE]")
                        .build()));

        mockMvc.perform(post("/api/v1/ai/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"llmKeyId\":1,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"))
                .andExpect(status().isOk());

        // 不应调 saveMessage(strategyId null 分支)
        verify(messageService, never()).saveMessage(anyLong(), anyLong(), anyString(), anyString(), any());
    }

    @Test
    void postChat_whenEmptyMessages_shouldNotSaveMessage() throws Exception {
        // messages 空数组时不应存消息(防御边界)
        when(aiChatService.chat(any(AiChatRequest.class), eq(42L)))
                .thenReturn(Flux.just(ServerSentEvent.<String>builder()
                        .event("done")
                        .data("[DONE]")
                        .build()));

        // 注:@Valid @NotNull 阻止 messages=null,但空 List 可过校验(@Size(max=100) 只限上界)
        mockMvc.perform(post("/api/v1/ai/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"llmKeyId\":1,\"strategyId\":5,\"messages\":[]}"))
                .andExpect(status().isOk());

        verify(messageService, never()).saveMessage(anyLong(), anyLong(), anyString(), anyString(), any());
    }
}
