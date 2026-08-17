package com.kwikquant.ai.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.kwikquant.account.application.LlmApiKeyService;
import com.kwikquant.account.domain.LlmApiKey;
import com.kwikquant.ai.domain.AiUsageSource;
import com.kwikquant.shared.types.LlmProvider;
import com.kwikquant.strategy.application.CodeSource;
import com.kwikquant.strategy.application.StrategyCodeService;
import com.kwikquant.strategy.application.StrategyCrudService;
import com.kwikquant.strategy.domain.StrategyCode;
import com.kwikquant.strategy.domain.StrategyDefinition;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

class AiChatServiceTest {

    private LlmApiKeyService keyService;
    private StrategyCrudService crudService;
    private StrategyCodeService codeService;
    private ContextWindowManager ctxManager;
    private AiChatMessageService messageService;
    private AiUsageLogService usageLogService;
    private LlmProviderAdapter openaiAdapter;
    private AiChatService service;

    @BeforeEach
    void setUp() {
        keyService = mock(LlmApiKeyService.class);
        crudService = mock(StrategyCrudService.class);
        codeService = mock(StrategyCodeService.class);
        ctxManager = mock(ContextWindowManager.class);
        messageService = mock(AiChatMessageService.class);
        usageLogService = mock(AiUsageLogService.class);
        openaiAdapter = mock(LlmProviderAdapter.class);
        when(openaiAdapter.provider()).thenReturn(LlmProvider.OPENAI);
        // 默认 passthrough:返回入参 messages 不变(无压缩、无落库),让所有非压缩测试沿用原行为
        // (压缩行为本身由 ContextWindowManagerTest 直接验;本类只验 chat() 调 ctxManager + 用返回值)
        when(ctxManager.compress(any(), any(), any(), anyInt(), any()))
                .thenAnswer(inv -> new ContextWindowManager.CompressionResult(inv.getArgument(0), null));
        service = new AiChatService(
                keyService,
                crudService,
                codeService,
                ctxManager,
                messageService,
                usageLogService,
                List.of(openaiAdapter));
    }

    @Test
    void chat_normalStream_mapsDeltasToSseMessages() {
        LlmApiKey key = key(1L, LlmProvider.OPENAI, null);
        when(keyService.getOwned(1L, 42L)).thenReturn(key);
        when(keyService.decryptSecret(key)).thenReturn("sk-secret");
        when(openaiAdapter.stream(any(), any())).thenReturn(Flux.just("Hello", " world"));

        AiChatRequest req =
                new AiChatRequest(1L, List.of(new ChatMessage("user", "hi")), null, null, null, null, null, null);
        List<ServerSentEvent<String>> events =
                service.chat(req, 42L).collectList().block();

        assertNotNull(events);
        assertEquals(3, events.size());
        assertEquals("message", events.get(0).event());
        assertEquals("Hello", events.get(0).data());
        // Flux 末尾发 event:done 终止帧（区分正常结束 vs 断连）
        assertEquals("done", events.get(2).event());

        // 负分支断言：strategyId=null 时不应注入 system prompt（if 分支的 false 路径）
        var captor = org.mockito.ArgumentCaptor.forClass(LlmStreamRequest.class);
        verify(openaiAdapter).stream(captor.capture(), any());
        LlmStreamRequest passed = captor.getValue();
        assertTrue(
                passed.messages().stream().noneMatch(m -> "system".equals(m.role())),
                "no system prompt should be injected when strategyId is null");
    }

    @Test
    void chat_strategyContextInjectsSystemPromptFirst() {
        LlmApiKey key = key(1L, LlmProvider.OPENAI, null);
        when(keyService.getOwned(1L, 42L)).thenReturn(key);
        when(keyService.decryptSecret(key)).thenReturn("sk");
        StrategyDefinition s = StrategyDefinition.create(42L, "MA", null, "BTC/USDT", "BINANCE", "SPOT", "1h", "{}");
        s.setId(5L);
        when(crudService.getOwned(5L, 42L)).thenReturn(s);
        when(openaiAdapter.stream(any(), any())).thenReturn(Flux.just("ok"));

        AiChatRequest req = new AiChatRequest(
                1L,
                List.of(new ChatMessage("user", "optimize")),
                5L,
                null,
                null,
                null,
                "print('existing')",
                CodeSource.EDITOR);
        service.chat(req, 42L).collectList().block();

        var captor = org.mockito.ArgumentCaptor.forClass(LlmStreamRequest.class);
        verify(openaiAdapter).stream(captor.capture(), any());
        LlmStreamRequest passed = captor.getValue();
        assertEquals("system", passed.messages().get(0).role());
        assertTrue(passed.messages().get(0).content().contains("MA"));
        assertTrue(passed.messages().get(0).content().contains("BTC/USDT"));
    }

    // ---------- sourceCode 注入 + 截断兜底 + codeSource 分支 ----------

    @Test
    void chat_truncatesMessagesExceeding100_toMostRecent100() {
        // 服务端截断:前端发全量历史(≤200),截到最近 100 发 LLM(防 provider context 溢出 400)
        LlmApiKey key = key(1L, LlmProvider.OPENAI, null);
        when(keyService.getOwned(1L, 42L)).thenReturn(key);
        when(keyService.decryptSecret(key)).thenReturn("sk");
        when(openaiAdapter.stream(any(), any())).thenReturn(Flux.just("ok"));
        // 150 条历史(无 strategyId → 不注入 system,纯历史截断)
        java.util.List<ChatMessage> msgs = new java.util.ArrayList<>();
        for (int i = 0; i < 150; i++) msgs.add(new ChatMessage("user", "msg-" + i));
        AiChatRequest req = new AiChatRequest(1L, msgs, null, null, null, null, null, CodeSource.EDITOR);
        service.chat(req, 42L).collectList().block();

        var captor = org.mockito.ArgumentCaptor.forClass(LlmStreamRequest.class);
        verify(openaiAdapter).stream(captor.capture(), any());
        assertEquals(100, captor.getValue().messages().size());
        // 截到最近 100:最后一条是 msg-149(索引 149)
        assertEquals("msg-149", captor.getValue().messages().get(99).content());
    }

    @Test
    void chat_injectsEditorSourceCodeIntoSystemPrompt() {
        // editor 模式前端传 sourceCode,后端注入 system prompt 代码块(混合方案)
        LlmApiKey key = key(1L, LlmProvider.OPENAI, null);
        when(keyService.getOwned(1L, 42L)).thenReturn(key);
        when(keyService.decryptSecret(key)).thenReturn("sk");
        StrategyDefinition s = StrategyDefinition.create(42L, "MA", null, "BTC/USDT", "BINANCE", "SPOT", "1h", "{}");
        s.setId(5L);
        when(crudService.getOwned(5L, 42L)).thenReturn(s);
        when(openaiAdapter.stream(any(), any())).thenReturn(Flux.just("ok"));

        AiChatRequest req = new AiChatRequest(
                1L,
                List.of(new ChatMessage("user", "optimize")),
                5L,
                null,
                null,
                null,
                "print('x')",
                CodeSource.EDITOR);
        service.chat(req, 42L).collectList().block();

        var captor = org.mockito.ArgumentCaptor.forClass(LlmStreamRequest.class);
        verify(openaiAdapter).stream(captor.capture(), any());
        LlmStreamRequest passed = captor.getValue();
        assertEquals("system", passed.messages().get(0).role());
        assertTrue(
                passed.messages().get(0).content().contains("print('x')"), "editor sourceCode 应注入 system prompt 代码块");
    }

    @Test
    void chat_truncatesSourceCodeExceeding80kChars() {
        // 截断兜底:service 内构造的 system message 不经 @Size,buildSystemPrompt 按字符数截断(8 万)
        LlmApiKey key = key(1L, LlmProvider.OPENAI, null);
        when(keyService.getOwned(1L, 42L)).thenReturn(key);
        when(keyService.decryptSecret(key)).thenReturn("sk");
        StrategyDefinition s = StrategyDefinition.create(42L, "MA", null, "BTC/USDT", "BINANCE", "SPOT", "1h", "{}");
        s.setId(5L);
        when(crudService.getOwned(5L, 42L)).thenReturn(s);
        when(openaiAdapter.stream(any(), any())).thenReturn(Flux.just("ok"));
        String huge = "x".repeat(90_000);

        AiChatRequest req = new AiChatRequest(
                1L, List.of(new ChatMessage("user", "edit")), 5L, null, null, null, huge, CodeSource.EDITOR);
        service.chat(req, 42L).collectList().block();

        var captor = org.mockito.ArgumentCaptor.forClass(LlmStreamRequest.class);
        verify(openaiAdapter).stream(captor.capture(), any());
        String systemContent = captor.getValue().messages().get(0).content();
        assertTrue(systemContent.contains("truncated"), "超 8 万字符 sourceCode 应截断并标注");
        assertTrue(
                systemContent.contains("exceeds 80000 chars"),
                "截断提示应注明 8 万字符阈值,实际: " + systemContent.substring(systemContent.indexOf("truncat")));
    }

    @Test
    void chat_injectsDraftCodeFromBackend_whenCodeSourceDraft() {
        // DRAFT 模式不传 sourceCode,后端按 strategyId 取 DRAFT 注入(省 1MB body + audit 可信)
        LlmApiKey key = key(1L, LlmProvider.OPENAI, null);
        when(keyService.getOwned(1L, 42L)).thenReturn(key);
        when(keyService.decryptSecret(key)).thenReturn("sk");
        StrategyDefinition s = StrategyDefinition.create(42L, "MA", null, "BTC/USDT", "BINANCE", "SPOT", "1h", "{}");
        s.setId(5L);
        when(crudService.getOwned(5L, 42L)).thenReturn(s);
        StrategyCode draft = StrategyCode.create(5L, 1, "draft code body", "v1");
        when(codeService.getDraftCodeOwned(5L, 42L)).thenReturn(draft);
        when(openaiAdapter.stream(any(), any())).thenReturn(Flux.just("ok"));

        AiChatRequest req = new AiChatRequest(
                1L, List.of(new ChatMessage("user", "edit")), 5L, null, null, null, null, CodeSource.DRAFT);
        service.chat(req, 42L).collectList().block();

        var captor = org.mockito.ArgumentCaptor.forClass(LlmStreamRequest.class);
        verify(openaiAdapter).stream(captor.capture(), any());
        LlmStreamRequest passed = captor.getValue();
        assertEquals("system", passed.messages().get(0).role());
        assertTrue(
                passed.messages().get(0).content().contains("draft code body"),
                "DRAFT 模式应从 codeService.getDraftCodeOwned 注入 sourceCode");
        verify(codeService).getDraftCodeOwned(5L, 42L);
    }

    @Test
    void chat_unsupportedProviderThrows() {
        LlmApiKey key = key(1L, LlmProvider.ANTHROPIC, null); // 无 ANTHROPIC adapter
        when(keyService.getOwned(1L, 42L)).thenReturn(key);

        AiChatRequest req =
                new AiChatRequest(1L, List.of(new ChatMessage("user", "hi")), null, null, null, null, null, null);
        assertThrows(
                com.kwikquant.ai.domain.LlmProviderNotSupportedException.class,
                () -> service.chat(req, 42L).collectList().block());
    }

    @Test
    void chat_providerError401_sanitizesToKeyInvalid() {
        LlmApiKey key = key(1L, LlmProvider.OPENAI, null);
        when(keyService.getOwned(1L, 42L)).thenReturn(key);
        when(keyService.decryptSecret(key)).thenReturn("sk");
        when(openaiAdapter.stream(any(), any()))
                .thenReturn(Flux.error(new LlmProviderException(401, "invalid_api_key")));

        AiChatRequest req =
                new AiChatRequest(1L, List.of(new ChatMessage("user", "hi")), null, null, null, null, null, null);
        List<ServerSentEvent<String>> events =
                service.chat(req, 42L).collectList().block();

        assertNotNull(events);
        assertEquals(2, events.size());
        assertEquals("error", events.get(0).event());
        assertEquals("API key invalid or expired", events.get(0).data());
        // error 路径经 onErrorResume 后也 concat done 终止帧
        assertEquals("done", events.get(1).event());
    }

    @Test
    void chat_providerError403_sanitizesToKeyInvalid() {
        // 覆盖 sanitize 里 `s == 401 || s == 403` 的第二个分支（403 也走 key-invalid 脱敏）
        LlmApiKey key = key(1L, LlmProvider.OPENAI, null);
        when(keyService.getOwned(1L, 42L)).thenReturn(key);
        when(keyService.decryptSecret(key)).thenReturn("sk");
        when(openaiAdapter.stream(any(), any())).thenReturn(Flux.error(new LlmProviderException(403, "forbidden")));

        AiChatRequest req =
                new AiChatRequest(1L, List.of(new ChatMessage("user", "hi")), null, null, null, null, null, null);
        List<ServerSentEvent<String>> events =
                service.chat(req, 42L).collectList().block();

        assertNotNull(events);
        assertEquals(2, events.size());
        assertEquals("error", events.get(0).event());
        assertEquals("API key invalid or expired", events.get(0).data());
        // error 路径经 onErrorResume 后也 concat done 终止帧
        assertEquals("done", events.get(1).event());
    }

    @Test
    void chat_providerError429_sanitizesToRateLimit() {
        LlmApiKey key = key(1L, LlmProvider.OPENAI, null);
        when(keyService.getOwned(1L, 42L)).thenReturn(key);
        when(keyService.decryptSecret(key)).thenReturn("sk");
        when(openaiAdapter.stream(any(), any())).thenReturn(Flux.error(new LlmProviderException(429, "slow down")));

        AiChatRequest req =
                new AiChatRequest(1L, List.of(new ChatMessage("user", "hi")), null, null, null, null, null, null);
        List<ServerSentEvent<String>> events =
                service.chat(req, 42L).collectList().block();

        assertEquals("Rate limit exceeded, please retry later", events.get(0).data());
    }

    @Test
    void chat_providerError500_sanitizesToUnavailable() {
        LlmApiKey key = key(1L, LlmProvider.OPENAI, null);
        when(keyService.getOwned(1L, 42L)).thenReturn(key);
        when(keyService.decryptSecret(key)).thenReturn("sk");
        when(openaiAdapter.stream(any(), any())).thenReturn(Flux.error(new LlmProviderException(500, "oom")));

        AiChatRequest req =
                new AiChatRequest(1L, List.of(new ChatMessage("user", "hi")), null, null, null, null, null, null);
        List<ServerSentEvent<String>> events =
                service.chat(req, 42L).collectList().block();

        assertEquals("LLM provider service unavailable", events.get(0).data());
    }

    @Test
    void chat_streamInterrupted_genericErrorSanitized() {
        LlmApiKey key = key(1L, LlmProvider.OPENAI, null);
        when(keyService.getOwned(1L, 42L)).thenReturn(key);
        when(keyService.decryptSecret(key)).thenReturn("sk");
        when(openaiAdapter.stream(any(), any())).thenReturn(Flux.error(new RuntimeException("conn reset")));

        AiChatRequest req =
                new AiChatRequest(1L, List.of(new ChatMessage("user", "hi")), null, null, null, null, null, null);
        List<ServerSentEvent<String>> events =
                service.chat(req, 42L).collectList().block();

        assertEquals("Stream interrupted", events.get(0).data());
    }

    @Test
    void chat_userOverridesTemperatureAndMaxTokens() {
        // AiChatRequest.temperatureOrDefault/maxTokensOrDefault 的 non-null 分支 —— 之前所有
        // test case 都传 null（走默认 0.7 / 4096），这个分支从未被覆盖。若默认值判断反了（`== null` 变 `!= null`），
        // 原有测试仍绿；这里断言用户传的 0.3 / 1024 会覆盖默认值。
        LlmApiKey key = key(1L, LlmProvider.OPENAI, null);
        when(keyService.getOwned(1L, 42L)).thenReturn(key);
        when(keyService.decryptSecret(key)).thenReturn("sk");
        when(openaiAdapter.stream(any(), any())).thenReturn(Flux.just("x"));

        AiChatRequest req = new AiChatRequest(
                1L, List.of(new ChatMessage("user", "hi")), null, "gpt-4o-mini", 0.3, 1024, null, null);
        service.chat(req, 42L).collectList().block();

        var captor = org.mockito.ArgumentCaptor.forClass(LlmStreamRequest.class);
        verify(openaiAdapter).stream(captor.capture(), any());
        LlmStreamRequest passed = captor.getValue();
        assertEquals(0.3, passed.temperature());
        assertEquals(1024, passed.maxTokens());
        assertEquals("gpt-4o-mini", passed.model());
    }

    @Test
    void chat_passesDefaultsWhenModelAndParamsNull() {
        LlmApiKey key = key(1L, LlmProvider.OPENAI, null);
        when(keyService.getOwned(1L, 42L)).thenReturn(key);
        when(keyService.decryptSecret(key)).thenReturn("sk");
        when(openaiAdapter.stream(any(), any())).thenReturn(Flux.just("x"));

        AiChatRequest req =
                new AiChatRequest(1L, List.of(new ChatMessage("user", "hi")), null, null, null, null, null, null);
        service.chat(req, 42L).collectList().block();

        var captor = org.mockito.ArgumentCaptor.forClass(LlmStreamRequest.class);
        verify(openaiAdapter).stream(captor.capture(), any());
        LlmStreamRequest passed = captor.getValue();
        assertEquals(0.7, passed.temperature());
        assertEquals(4096, passed.maxTokens());
        assertNull(passed.model());
        assertEquals("sk", passed.apiSecret());
    }

    @Test
    void chat_openAiCompatiblePassesBaseUrl() {
        LlmApiKey key = key(1L, LlmProvider.OPENAI_COMPATIBLE, "https://api.deepseek.com/v1");
        when(keyService.getOwned(1L, 42L)).thenReturn(key);
        when(keyService.decryptSecret(key)).thenReturn("sk");
        LlmProviderAdapter compatAdapter = mock(LlmProviderAdapter.class);
        when(compatAdapter.provider()).thenReturn(LlmProvider.OPENAI_COMPATIBLE);
        when(compatAdapter.stream(any(), any())).thenReturn(Flux.just("x"));
        service = new AiChatService(
                keyService,
                crudService,
                codeService,
                ctxManager,
                messageService,
                usageLogService,
                List.of(openaiAdapter, compatAdapter));

        AiChatRequest req =
                new AiChatRequest(1L, List.of(new ChatMessage("user", "hi")), null, null, null, null, null, null);
        service.chat(req, 42L).collectList().block();

        var captor = org.mockito.ArgumentCaptor.forClass(LlmStreamRequest.class);
        verify(compatAdapter).stream(captor.capture(), any());
        assertEquals("https://api.deepseek.com/v1", captor.getValue().baseUrl());
    }

    private LlmApiKey key(long id, LlmProvider provider, String baseUrl) {
        LlmApiKey k = new LlmApiKey();
        k.setId(id);
        k.setUserId(42L);
        k.setProvider(provider);
        k.setBaseUrl(baseUrl);
        return k;
    }

    private LlmApiKey key(long id, LlmProvider provider, String baseUrl, String model) {
        LlmApiKey k = key(id, provider, baseUrl);
        // v2: availableModels raw JSON(defaultModelOf 取首项;chat() fallback 走 keyService.defaultModelOf)
        k.setAvailableModels("[\"" + model + "\"]");
        return k;
    }

    // ---------- model 优先级 request.model() > key.getModel() > adapter.defaultModel() ----------

    @Test
    void chat_whenRequestModelPresent_shouldUseRequestModel() {
        // 优先级 1: request.model() 非空 → 透传(即使 key.getModel() 也非空,会话级覆盖 key 级默认)
        LlmApiKey k = key(1L, LlmProvider.OPENAI, null, "gpt-4o");
        when(keyService.getOwned(1L, 42L)).thenReturn(k);
        when(keyService.decryptSecret(k)).thenReturn("sk");
        when(openaiAdapter.stream(any(), any())).thenReturn(Flux.just("ok"));

        AiChatRequest req = new AiChatRequest(
                1L, List.of(new ChatMessage("user", "hi")), null, "gpt-4o-mini", null, null, null, null);
        service.chat(req, 42L).collectList().block();

        var captor = org.mockito.ArgumentCaptor.forClass(LlmStreamRequest.class);
        verify(openaiAdapter).stream(captor.capture(), any());
        assertEquals("gpt-4o-mini", captor.getValue().model());
    }

    @Test
    void chat_whenRequestModelNullKeyModelPresent_shouldUseKeyModel() {
        // 优先级 2: request.model() null + key.getModel() 非空 → 用 key 级默认 model
        LlmApiKey k = key(1L, LlmProvider.OPENAI_COMPATIBLE, "https://gw.example.com/v1", "deepseek-chat");
        when(keyService.getOwned(1L, 42L)).thenReturn(k);
        when(keyService.decryptSecret(k)).thenReturn("sk");
        when(keyService.defaultModelOf(k)).thenReturn("deepseek-chat");
        LlmProviderAdapter compatAdapter = mock(LlmProviderAdapter.class);
        when(compatAdapter.provider()).thenReturn(LlmProvider.OPENAI_COMPATIBLE);
        when(compatAdapter.stream(any(), any())).thenReturn(Flux.just("x"));
        service = new AiChatService(
                keyService,
                crudService,
                codeService,
                ctxManager,
                messageService,
                usageLogService,
                List.of(openaiAdapter, compatAdapter));

        // request.model() = null(第 4 位),key.getModel() = "deepseek-chat"
        AiChatRequest req =
                new AiChatRequest(1L, List.of(new ChatMessage("user", "hi")), null, null, null, null, null, null);
        service.chat(req, 42L).collectList().block();

        var captor = org.mockito.ArgumentCaptor.forClass(LlmStreamRequest.class);
        verify(compatAdapter).stream(captor.capture(), any());
        assertEquals("deepseek-chat", captor.getValue().model());
    }

    @Test
    void chat_whenRequestModelBlankString_fallsBackToKeyModel() {
        // M3 空串边界:request.model()="" 视同未传(isBlank),fallback key.getModel()
        // (防前端误传 "" 致 adapter 用 "" 当 model 名报 "model not found" 而非 fallback)
        LlmApiKey k = key(1L, LlmProvider.OPENAI_COMPATIBLE, "https://gw.example.com/v1", "deepseek-chat");
        when(keyService.getOwned(1L, 42L)).thenReturn(k);
        when(keyService.decryptSecret(k)).thenReturn("sk");
        when(keyService.defaultModelOf(k)).thenReturn("deepseek-chat");
        LlmProviderAdapter compatAdapter = mock(LlmProviderAdapter.class);
        when(compatAdapter.provider()).thenReturn(LlmProvider.OPENAI_COMPATIBLE);
        when(compatAdapter.stream(any(), any())).thenReturn(Flux.just("x"));
        service = new AiChatService(
                keyService,
                crudService,
                codeService,
                ctxManager,
                messageService,
                usageLogService,
                List.of(openaiAdapter, compatAdapter));

        // request.model() = "" (空串),key.getModel() = "deepseek-chat" → isBlank 视空串为未传,fallback key model
        AiChatRequest req =
                new AiChatRequest(1L, List.of(new ChatMessage("user", "hi")), null, "", null, null, null, null);
        service.chat(req, 42L).collectList().block();

        var captor = org.mockito.ArgumentCaptor.forClass(LlmStreamRequest.class);
        verify(compatAdapter).stream(captor.capture(), any());
        assertEquals("deepseek-chat", captor.getValue().model());
    }

    @Test
    void chat_whenBothNullCompatible_shouldThrowLlmProviderExceptionZero() {
        // 优先级 3(末端): request.model() + key.getModel() 都 null + OPENAI_COMPATIBLE
        // → AiChatService 传 null 给 LlmStreamRequest,真 adapter 会 Flux.error(LlmProviderException(0))
        // (AbstractOpenAiAdapter.stream line 39-41 已覆盖)。这里用 mock adapter 模拟该 error 路径,
        // 验证 chat() 在两者都 null 时确实把 null 透传(而非自作主张报错),把 defaultModel 职责留给 adapter。
        // 名字 "shouldThrowLlmProviderExceptionZero" 指 adapter 层抛 LlmProviderException(0);chat() 经
        // onErrorResume 把它转成 SSE error event(sanitize 已扩 status=0 分支)。
        LlmApiKey k = key(1L, LlmProvider.OPENAI_COMPATIBLE, "https://gw.example.com/v1");
        when(keyService.getOwned(1L, 42L)).thenReturn(k);
        when(keyService.decryptSecret(k)).thenReturn("sk");
        LlmProviderAdapter compatAdapter = mock(LlmProviderAdapter.class);
        when(compatAdapter.provider()).thenReturn(LlmProvider.OPENAI_COMPATIBLE);
        // 模拟真 adapter 行为:model==null → Flux.error(LlmProviderException(0))
        when(compatAdapter.stream(any(), any()))
                .thenReturn(Flux.error(new LlmProviderException(0, "model is required")));
        service = new AiChatService(
                keyService,
                crudService,
                codeService,
                ctxManager,
                messageService,
                usageLogService,
                List.of(openaiAdapter, compatAdapter));

        AiChatRequest req =
                new AiChatRequest(1L, List.of(new ChatMessage("user", "hi")), null, null, null, null, null, null);
        List<ServerSentEvent<String>> events =
                service.chat(req, 42L).collectList().block();

        assertNotNull(events);
        // chat() 透传 null → adapter 报 LlmProviderException(0) → onErrorResume 转 SSE error
        assertEquals("error", events.get(0).event());
        var captor = org.mockito.ArgumentCaptor.forClass(LlmStreamRequest.class);
        verify(compatAdapter).stream(captor.capture(), any());
        assertNull(captor.getValue().model());
    }

    // ---------- testConnection ----------

    @Test
    void testConnection_success_returnsOk() {
        LlmApiKey key = key(1L, LlmProvider.OPENAI, null);
        when(keyService.getOwned(1L, 42L)).thenReturn(key);
        when(keyService.decryptSecret(key)).thenReturn("sk");
        when(openaiAdapter.stream(any(), any())).thenReturn(Flux.just("hi"));

        AiChatService.LlmConnectionTestResult result = service.testConnection(1L, "gpt-5.6", 42L);

        assertTrue(result.success());
        assertEquals("ok", result.message());
    }

    @Test
    void testConnection_providerError_returnsSanitized() {
        LlmApiKey key = key(1L, LlmProvider.OPENAI, null);
        when(keyService.getOwned(1L, 42L)).thenReturn(key);
        when(keyService.decryptSecret(key)).thenReturn("sk");
        when(openaiAdapter.stream(any(), any())).thenReturn(Flux.error(new LlmProviderException(401, "invalid key")));

        AiChatService.LlmConnectionTestResult result = service.testConnection(1L, "gpt-5.6", 42L);

        assertFalse(result.success());
        assertNotNull(result.message()); // sanitize(401) 脱敏文案,非透传 provider 原始 "invalid key"
    }

    @Test
    void testConnection_otherException_returnsStreamInterrupted() {
        LlmApiKey key = key(1L, LlmProvider.OPENAI, null);
        when(keyService.getOwned(1L, 42L)).thenReturn(key);
        when(keyService.decryptSecret(key)).thenReturn("sk");
        // 模拟 timeout/网络异常(10s 超时或 reactor 内部异常,catch Exception 兜底)
        when(openaiAdapter.stream(any(), any()))
                .thenReturn(Flux.error(new java.util.concurrent.TimeoutException("test")));

        AiChatService.LlmConnectionTestResult result = service.testConnection(1L, "gpt-5.6", 42L);

        assertFalse(result.success());
        assertEquals("Stream interrupted", result.message());
    }

    @Test
    void chat_whenBothNullOpenAI_shouldUseDefaultGpt4o() {
        // 优先级 3(末端): request.model() + key.getModel() 都 null + OPENAI
        // → AiChatService 传 null 给 LlmStreamRequest,真 OpenAiAdapter 会 fallback 到 defaultModel()="gpt-4o"
        // (AbstractOpenAiAdapter.stream line 38 已覆盖)。这里用 mock adapter 验证 chat() 把 null 透传,
        // 不自行解析 "gpt-4o"(defaultModel() 是 adapter protected 方法,跨模块不可调,设计伪代码的
        // adapter.defaultModel() 由 adapter 内部 fallback 实现,行为等价)。
        LlmApiKey k = key(1L, LlmProvider.OPENAI, null);
        when(keyService.getOwned(1L, 42L)).thenReturn(k);
        when(keyService.decryptSecret(k)).thenReturn("sk");
        when(openaiAdapter.stream(any(), any())).thenReturn(Flux.just("ok"));

        AiChatRequest req =
                new AiChatRequest(1L, List.of(new ChatMessage("user", "hi")), null, null, null, null, null, null);
        service.chat(req, 42L).collectList().block();

        var captor = org.mockito.ArgumentCaptor.forClass(LlmStreamRequest.class);
        verify(openaiAdapter).stream(captor.capture(), any());
        // chat() 把 null 透传,真 OpenAiAdapter 会用 defaultModel()="gpt-4o"(adapter 测试已覆盖)
        assertNull(captor.getValue().model());
    }

    // ---------- sanitize 各分支(7 档脱敏文案) ----------
    // sanitize 是 package-private static,直接调用避免走整个 chat 流,断言聚焦脱敏分类逻辑本身。
    // 现有 chat_providerError401/403/429/500/streamInterrupted 通过 chat 流间接覆盖 sanitize,但仅覆盖
    // 5 档,缺 status=0/-1/非标准4xx 三档,这里独立 unit test 覆盖全 7 档 + 边界。

    @Test
    void sanitize_whenStatus0_shouldReturnModelNotSpecified() {
        // status=0: adapter 检测到 model 缺失(AbstractOpenAiAdapter.stream)→
        // status=0 分支给出可操作文案"模型未指定,请在会话栏选择模型"(model 选择在会话栏 combobox)
        assertEquals("模型未指定,请在会话栏选择模型", AiChatService.sanitize(new LlmProviderException(0, "model is required")));
    }

    @Test
    void sanitize_whenStatusMinus1_shouldReturnNetworkUnreachable() {
        // status=-1: adapter 把 WebClientRequestException(网络层:连接超时/被墙/DNS 失败)包装成
        // LlmProviderException(-1)(AbstractOpenAiAdapter/AnthropicAdapter);
        // status=-1 分支给出可操作文案"无法连接 LLM provider,请检查网络/代理/baseUrl"
        assertEquals(
                "无法连接 LLM provider,请检查网络/代理/baseUrl",
                AiChatService.sanitize(new LlmProviderException(-1, "network: connect refused")));
    }

    @Test
    void sanitize_whenStatus401_shouldReturnApiKeyInvalid() {
        // 401: provider 返 API key 无效/过期。已有 chat_providerError401_sanitizesToKeyInvalid 间接覆盖,
        // 这里独立 unit test 锁定文案,防止后续重构误改。
        assertEquals(
                "API key invalid or expired", AiChatService.sanitize(new LlmProviderException(401, "invalid_api_key")));
    }

    @Test
    void sanitize_whenStatus403_shouldReturnApiKeyInvalid() {
        // 403: provider 返 forbidden(权限不足/key 失效)。与 401 同档脱敏,不区分以便不泄露具体差异。
        assertEquals("API key invalid or expired", AiChatService.sanitize(new LlmProviderException(403, "forbidden")));
    }

    @Test
    void sanitize_whenStatus429_shouldReturnRateLimit() {
        // 429: provider 限流,提示稍后重试(避免用户连点加剧)。
        assertEquals(
                "Rate limit exceeded, please retry later",
                AiChatService.sanitize(new LlmProviderException(429, "slow down")));
    }

    @Test
    void sanitize_whenStatus500_shouldReturnServiceUnavailable() {
        // >=500: provider 服务端故障(oom/503 维护中等),提示 provider 不可用。
        assertEquals(
                "LLM provider service unavailable",
                AiChatService.sanitize(new LlmProviderException(500, "internal error")));
    }

    @Test
    void sanitize_whenStatus503_shouldReturnServiceUnavailable() {
        // 503 边界:验证 >=500 用 >= 而非 == 500,503 也走 service unavailable。
        assertEquals(
                "LLM provider service unavailable",
                AiChatService.sanitize(new LlmProviderException(503, "maintenance")));
    }

    @Test
    void sanitize_whenStatus404_shouldReturnProviderErrorWithStatus() {
        // 非标准 4xx(404):provider 返 not found(常见:模型名写错 / endpoint 拼错)。sanitize
        // 加通用兜底 "LLM provider 返回错误(状态码 N,可能模型名无效)" 透传状态码助排错,
        // 但不透传 provider body(避免泄露 baseUrl/账户片段)。
        assertEquals(
                "LLM provider 返回错误(状态码 404,可能模型名无效)",
                AiChatService.sanitize(new LlmProviderException(404, "model not found")));
    }

    @Test
    void sanitize_whenStatus400_shouldReturnProviderErrorWithStatus() {
        // 非标准 4xx(400):provider 返 bad request(常见:messages 格式错)。同 404 走通用兜底。
        assertEquals(
                "LLM provider 返回错误(状态码 400,可能模型名无效)",
                AiChatService.sanitize(new LlmProviderException(400, "bad request")));
    }

    @Test
    void sanitize_whenUnknownException_shouldReturnStreamInterrupt() {
        // 非 LlmProviderException(reactor 内部错/未分类异常):仍走 fallback "Stream interrupted"。
        // "Stream interrupted" 仅剩真正未分类异常,不再吞掉 status=0/-1。
        assertEquals("Stream interrupted", AiChatService.sanitize(new RuntimeException("conn reset")));
    }

    // ---------- 上下文压缩(chat() → ContextWindowManager.compress 集成) ----------
    // 压缩算法本身由 ContextWindowManagerTest 直接验;本节只验 chat() 调 ctxManager +
    // 用返回值 + summary 落库契约。默认 passthrough stub(返回入参不变、summary=null)在 setUp 配置。

    @Test
    void chat_callsCtxManagerAndUsesReturnedMessages() {
        // ctxManager 返回的 messages(非入参)应直传 adapter.stream;compress 被调且参数含 provider/model。
        LlmApiKey key = key(1L, LlmProvider.OPENAI, null);
        when(keyService.getOwned(1L, 42L)).thenReturn(key);
        when(keyService.decryptSecret(key)).thenReturn("sk");
        when(keyService.defaultModelOf(key)).thenReturn("gpt-4o");
        when(openaiAdapter.stream(any(), any())).thenReturn(Flux.just("ok"));
        // 覆盖 setUp 的 passthrough:返回压缩后 messages(summary=null → 不落库)
        List<ChatMessage> compressed = List.of(new ChatMessage("assistant", "compressed-by-mgr"));
        when(ctxManager.compress(any(), any(), any(), anyInt(), any()))
                .thenReturn(new ContextWindowManager.CompressionResult(compressed, null));

        AiChatRequest req =
                new AiChatRequest(1L, List.of(new ChatMessage("user", "hi")), null, "gpt-4o", null, null, null, null);
        service.chat(req, 42L).collectList().block();

        // compress 被调,provider=OPENAI、model=gpt-4o 透传
        verify(ctxManager).compress(any(), eq(LlmProvider.OPENAI), eq("gpt-4o"), eq(4096), any());
        // 返回的 compressed(非入参 [user "hi"])直传 stream
        var captor = org.mockito.ArgumentCaptor.forClass(LlmStreamRequest.class);
        verify(openaiAdapter).stream(captor.capture(), any());
        assertEquals(1, captor.getValue().messages().size());
        assertEquals("compressed-by-mgr", captor.getValue().messages().get(0).content());
        // summary=null → 不落库
        verifyNoInteractions(messageService);
    }

    @Test
    void chat_whenSummaryNonNullAndStrategyIdNonNull_savesSummaryAsAssistantMessage() {
        // summary 非空 + strategyId 非空 → 落库为合成 assistant 消息("（对话摘要）"+summary)
        LlmApiKey key = key(1L, LlmProvider.OPENAI, null);
        when(keyService.getOwned(1L, 42L)).thenReturn(key);
        when(keyService.decryptSecret(key)).thenReturn("sk");
        StrategyDefinition s = StrategyDefinition.create(42L, "MA", null, "BTC/USDT", "BINANCE", "SPOT", "1h", "{}");
        s.setId(5L);
        when(crudService.getOwned(5L, 42L)).thenReturn(s);
        when(openaiAdapter.stream(any(), any())).thenReturn(Flux.just("ok"));
        when(ctxManager.compress(any(), any(), any(), anyInt(), any()))
                .thenAnswer(inv -> new ContextWindowManager.CompressionResult(inv.getArgument(0), "summary"));

        AiChatRequest req = new AiChatRequest(
                1L, List.of(new ChatMessage("user", "hi")), 5L, null, null, null, "print('x')", CodeSource.EDITOR);
        service.chat(req, 42L).collectList().block();

        // 落库:role=assistant,content="（对话摘要）summary",model=null(request 无 model 且 key 无默认)
        verify(messageService).saveMessage(5L, 42L, "assistant", "（对话摘要）summary", null);
    }

    @Test
    void chat_whenSummaryNonNullButStrategyIdNull_doesNotSave() {
        // 非 strategy 会话不持久化:summary 非空但 strategyId=null → 不落库
        LlmApiKey key = key(1L, LlmProvider.OPENAI, null);
        when(keyService.getOwned(1L, 42L)).thenReturn(key);
        when(keyService.decryptSecret(key)).thenReturn("sk");
        when(openaiAdapter.stream(any(), any())).thenReturn(Flux.just("ok"));
        when(ctxManager.compress(any(), any(), any(), anyInt(), any()))
                .thenAnswer(inv -> new ContextWindowManager.CompressionResult(inv.getArgument(0), "summary"));

        AiChatRequest req =
                new AiChatRequest(1L, List.of(new ChatMessage("user", "hi")), null, null, null, null, null, null);
        service.chat(req, 42L).collectList().block();

        verifyNoInteractions(messageService);
    }

    // ---------- V49:chat 流终止 usage 落库(AiUsageSource.CHAT) ----------

    @Test
    void chat_whenStreamEmitsUsage_logsUsageOnTermination() {
        // adapter mock 在返回 Flux 前调 sink.accept(10,20) 模拟真 adapter 从 usage 帧提取;
        // chat 的 doFinally 在流终止时落库,source=CHAT。验证 doFinally+sink+@Async 落库链路。
        LlmApiKey key = key(1L, LlmProvider.OPENAI, null);
        when(keyService.getOwned(1L, 42L)).thenReturn(key);
        when(keyService.decryptSecret(key)).thenReturn("sk");
        when(openaiAdapter.stream(any(), any())).thenAnswer(inv -> {
            UsageSink sink = inv.getArgument(1, UsageSink.class);
            sink.accept(10, 20);
            return Flux.just("ok");
        });

        AiChatRequest req =
                new AiChatRequest(1L, List.of(new ChatMessage("user", "hi")), null, null, null, null, null, null);
        service.chat(req, 42L).collectList().block();

        // doFinally 落库:userId=42,keyId=1,model=null(request 无 model 且 key 无默认),prompt=10,completion=20,source=CHAT
        var srcCaptor = org.mockito.ArgumentCaptor.forClass(AiUsageSource.class);
        verify(usageLogService).log(eq(42L), eq(1L), isNull(), eq(10), eq(20), srcCaptor.capture());
        assertEquals(AiUsageSource.CHAT, srcCaptor.getValue());
    }

    @Test
    void chat_whenStreamEmitsNoUsage_doesNotLog() {
        // adapter mock 不调 sink(模拟无 usage 帧的 provider/错误前流),sink 累计 0/0,
        // doFinally 的 `if (p>0||c>0)` 守卫跳过落库(防 0/0 噪声行)。
        LlmApiKey key = key(1L, LlmProvider.OPENAI, null);
        when(keyService.getOwned(1L, 42L)).thenReturn(key);
        when(keyService.decryptSecret(key)).thenReturn("sk");
        when(openaiAdapter.stream(any(), any())).thenReturn(Flux.just("ok"));

        AiChatRequest req =
                new AiChatRequest(1L, List.of(new ChatMessage("user", "hi")), null, null, null, null, null, null);
        service.chat(req, 42L).collectList().block();

        verifyNoInteractions(usageLogService);
    }

    // ---------- assistant 回复服务端持久化(取代前端 onClose 二次保存) ----------

    @Test
    void chat_whenStrategyIdPresent_persistsAssistantReplyOnComplete() {
        // 流正常结束(ON_COMPLETE):服务端累积的 delta 全文落库 role=assistant + model 溯源。
        // 服务端是流全文唯一权威来源,消除"关 tab/断网即丢"的客户端保存窗口。
        LlmApiKey key = key(1L, LlmProvider.OPENAI, null);
        when(keyService.getOwned(1L, 42L)).thenReturn(key);
        when(keyService.decryptSecret(key)).thenReturn("sk");
        StrategyDefinition s = StrategyDefinition.create(42L, "MA", null, "BTC/USDT", "BINANCE", "SPOT", "1h", "{}");
        s.setId(5L);
        when(crudService.getOwned(5L, 42L)).thenReturn(s);
        when(openaiAdapter.stream(any(), any())).thenReturn(Flux.just("建议", "优化 MA"));

        AiChatRequest req = new AiChatRequest(
                1L, List.of(new ChatMessage("user", "hi")), 5L, "gpt-4o", null, null, "print('x')", CodeSource.EDITOR);
        service.chat(req, 42L).collectList().block();

        verify(messageService).saveMessage(5L, 42L, "assistant", "建议优化 MA", "gpt-4o");
    }

    @Test
    void chat_whenStrategyIdNull_doesNotPersistReply() {
        // 无 strategyId 的会话不持久化(与 user 消息落库分支一致)
        LlmApiKey key = key(1L, LlmProvider.OPENAI, null);
        when(keyService.getOwned(1L, 42L)).thenReturn(key);
        when(keyService.decryptSecret(key)).thenReturn("sk");
        when(openaiAdapter.stream(any(), any())).thenReturn(Flux.just("ok"));

        AiChatRequest req =
                new AiChatRequest(1L, List.of(new ChatMessage("user", "hi")), null, null, null, null, null, null);
        service.chat(req, 42L).collectList().block();

        verifyNoInteractions(messageService);
    }

    @Test
    void chat_whenProviderError_doesNotPersistPartialReply() {
        // ON_ERROR(provider 中途失败):partial delta 不落库——只存完整成功回复,
        // 防历史里混入半截回复误导后续会话上下文。
        LlmApiKey key = key(1L, LlmProvider.OPENAI, null);
        when(keyService.getOwned(1L, 42L)).thenReturn(key);
        when(keyService.decryptSecret(key)).thenReturn("sk");
        StrategyDefinition s = StrategyDefinition.create(42L, "MA", null, "BTC/USDT", "BINANCE", "SPOT", "1h", "{}");
        s.setId(5L);
        when(crudService.getOwned(5L, 42L)).thenReturn(s);
        when(openaiAdapter.stream(any(), any()))
                .thenReturn(Flux.concat(Flux.just("partial"), Flux.error(new LlmProviderException(500, "oom"))));

        AiChatRequest req = new AiChatRequest(
                1L, List.of(new ChatMessage("user", "hi")), 5L, null, null, null, "print('x')", CodeSource.EDITOR);
        List<ServerSentEvent<String>> events =
                service.chat(req, 42L).collectList().block();

        assertNotNull(events); // onErrorResume 转 SSE error event,流仍正常收尾
        verify(messageService, never()).saveMessage(anyLong(), anyLong(), eq("assistant"), anyString(), any());
    }

    @Test
    void chat_whenBlankReply_doesNotPersist() {
        // 空白回复(0 delta / 全空白)不产生无意义空消息行
        LlmApiKey key = key(1L, LlmProvider.OPENAI, null);
        when(keyService.getOwned(1L, 42L)).thenReturn(key);
        when(keyService.decryptSecret(key)).thenReturn("sk");
        StrategyDefinition s = StrategyDefinition.create(42L, "MA", null, "BTC/USDT", "BINANCE", "SPOT", "1h", "{}");
        s.setId(5L);
        when(crudService.getOwned(5L, 42L)).thenReturn(s);
        when(openaiAdapter.stream(any(), any())).thenReturn(Flux.just("  ", "\n"));

        AiChatRequest req = new AiChatRequest(
                1L, List.of(new ChatMessage("user", "hi")), 5L, null, null, null, "print('x')", CodeSource.EDITOR);
        service.chat(req, 42L).collectList().block();

        verify(messageService, never()).saveMessage(anyLong(), anyLong(), eq("assistant"), anyString(), any());
    }

    @Test
    void chat_whenClientCancels_doesNotPersist() {
        // CANCEL(用户 abort / 关页):流未跑完,partial 不落库(与"只存完整成功回复"一致)
        LlmApiKey key = key(1L, LlmProvider.OPENAI, null);
        when(keyService.getOwned(1L, 42L)).thenReturn(key);
        when(keyService.decryptSecret(key)).thenReturn("sk");
        StrategyDefinition s = StrategyDefinition.create(42L, "MA", null, "BTC/USDT", "BINANCE", "SPOT", "1h", "{}");
        s.setId(5L);
        when(crudService.getOwned(5L, 42L)).thenReturn(s);
        when(openaiAdapter.stream(any(), any())).thenReturn(Flux.never());

        AiChatRequest req = new AiChatRequest(
                1L, List.of(new ChatMessage("user", "hi")), 5L, null, null, null, "print('x')", CodeSource.EDITOR);
        reactor.core.Disposable d = service.chat(req, 42L).subscribe();
        d.dispose();

        verify(messageService, never()).saveMessage(anyLong(), anyLong(), eq("assistant"), anyString(), any());
    }
}
