package com.kwikquant.strategy.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.kwikquant.account.application.LlmApiKeyService;
import com.kwikquant.account.domain.LlmApiKey;
import com.kwikquant.shared.types.LlmProvider;
import com.kwikquant.strategy.domain.StrategyDefinition;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

class AiChatServiceTest {

    private LlmApiKeyService keyService;
    private StrategyCrudService crudService;
    private LlmProviderAdapter openaiAdapter;
    private AiChatService service;

    @BeforeEach
    void setUp() {
        keyService = mock(LlmApiKeyService.class);
        crudService = mock(StrategyCrudService.class);
        openaiAdapter = mock(LlmProviderAdapter.class);
        when(openaiAdapter.provider()).thenReturn(LlmProvider.OPENAI);
        service = new AiChatService(keyService, crudService, List.of(openaiAdapter));
    }

    @Test
    void chat_normalStream_mapsDeltasToSseMessages() {
        LlmApiKey key = key(1L, LlmProvider.OPENAI, null);
        when(keyService.getOwned(1L, 42L)).thenReturn(key);
        when(keyService.decryptSecret(key)).thenReturn("sk-secret");
        when(openaiAdapter.stream(any())).thenReturn(Flux.just("Hello", " world"));

        AiChatRequest req = new AiChatRequest(1L, List.of(new ChatMessage("user", "hi")), null, null, null, null);
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
        verify(openaiAdapter).stream(captor.capture());
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
        when(openaiAdapter.stream(any())).thenReturn(Flux.just("ok"));

        AiChatRequest req = new AiChatRequest(1L, List.of(new ChatMessage("user", "optimize")), 5L, null, null, null);
        service.chat(req, 42L).collectList().block();

        var captor = org.mockito.ArgumentCaptor.forClass(LlmStreamRequest.class);
        verify(openaiAdapter).stream(captor.capture());
        LlmStreamRequest passed = captor.getValue();
        assertEquals("system", passed.messages().get(0).role());
        assertTrue(passed.messages().get(0).content().contains("MA"));
        assertTrue(passed.messages().get(0).content().contains("BTC/USDT"));
    }

    @Test
    void chat_unsupportedProviderThrows() {
        LlmApiKey key = key(1L, LlmProvider.ANTHROPIC, null); // 无 ANTHROPIC adapter
        when(keyService.getOwned(1L, 42L)).thenReturn(key);

        AiChatRequest req = new AiChatRequest(1L, List.of(new ChatMessage("user", "hi")), null, null, null, null);
        assertThrows(
                com.kwikquant.strategy.domain.LlmProviderNotSupportedException.class,
                () -> service.chat(req, 42L).collectList().block());
    }

    @Test
    void chat_providerError401_sanitizesToKeyInvalid() {
        LlmApiKey key = key(1L, LlmProvider.OPENAI, null);
        when(keyService.getOwned(1L, 42L)).thenReturn(key);
        when(keyService.decryptSecret(key)).thenReturn("sk");
        when(openaiAdapter.stream(any())).thenReturn(Flux.error(new LlmProviderException(401, "invalid_api_key")));

        AiChatRequest req = new AiChatRequest(1L, List.of(new ChatMessage("user", "hi")), null, null, null, null);
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
        when(openaiAdapter.stream(any())).thenReturn(Flux.error(new LlmProviderException(403, "forbidden")));

        AiChatRequest req = new AiChatRequest(1L, List.of(new ChatMessage("user", "hi")), null, null, null, null);
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
        when(openaiAdapter.stream(any())).thenReturn(Flux.error(new LlmProviderException(429, "slow down")));

        AiChatRequest req = new AiChatRequest(1L, List.of(new ChatMessage("user", "hi")), null, null, null, null);
        List<ServerSentEvent<String>> events =
                service.chat(req, 42L).collectList().block();

        assertEquals("Rate limit exceeded, please retry later", events.get(0).data());
    }

    @Test
    void chat_providerError500_sanitizesToUnavailable() {
        LlmApiKey key = key(1L, LlmProvider.OPENAI, null);
        when(keyService.getOwned(1L, 42L)).thenReturn(key);
        when(keyService.decryptSecret(key)).thenReturn("sk");
        when(openaiAdapter.stream(any())).thenReturn(Flux.error(new LlmProviderException(500, "oom")));

        AiChatRequest req = new AiChatRequest(1L, List.of(new ChatMessage("user", "hi")), null, null, null, null);
        List<ServerSentEvent<String>> events =
                service.chat(req, 42L).collectList().block();

        assertEquals("LLM provider service unavailable", events.get(0).data());
    }

    @Test
    void chat_streamInterrupted_genericErrorSanitized() {
        LlmApiKey key = key(1L, LlmProvider.OPENAI, null);
        when(keyService.getOwned(1L, 42L)).thenReturn(key);
        when(keyService.decryptSecret(key)).thenReturn("sk");
        when(openaiAdapter.stream(any())).thenReturn(Flux.error(new RuntimeException("conn reset")));

        AiChatRequest req = new AiChatRequest(1L, List.of(new ChatMessage("user", "hi")), null, null, null, null);
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
        when(openaiAdapter.stream(any())).thenReturn(Flux.just("x"));

        AiChatRequest req =
                new AiChatRequest(1L, List.of(new ChatMessage("user", "hi")), null, "gpt-4o-mini", 0.3, 1024);
        service.chat(req, 42L).collectList().block();

        var captor = org.mockito.ArgumentCaptor.forClass(LlmStreamRequest.class);
        verify(openaiAdapter).stream(captor.capture());
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
        when(openaiAdapter.stream(any())).thenReturn(Flux.just("x"));

        AiChatRequest req = new AiChatRequest(1L, List.of(new ChatMessage("user", "hi")), null, null, null, null);
        service.chat(req, 42L).collectList().block();

        var captor = org.mockito.ArgumentCaptor.forClass(LlmStreamRequest.class);
        verify(openaiAdapter).stream(captor.capture());
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
        when(compatAdapter.stream(any())).thenReturn(Flux.just("x"));
        service = new AiChatService(keyService, crudService, List.of(openaiAdapter, compatAdapter));

        AiChatRequest req = new AiChatRequest(1L, List.of(new ChatMessage("user", "hi")), null, null, null, null);
        service.chat(req, 42L).collectList().block();

        var captor = org.mockito.ArgumentCaptor.forClass(LlmStreamRequest.class);
        verify(compatAdapter).stream(captor.capture());
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
        when(openaiAdapter.stream(any())).thenReturn(Flux.just("ok"));

        AiChatRequest req =
                new AiChatRequest(1L, List.of(new ChatMessage("user", "hi")), null, "gpt-4o-mini", null, null);
        service.chat(req, 42L).collectList().block();

        var captor = org.mockito.ArgumentCaptor.forClass(LlmStreamRequest.class);
        verify(openaiAdapter).stream(captor.capture());
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
        when(compatAdapter.stream(any())).thenReturn(Flux.just("x"));
        service = new AiChatService(keyService, crudService, List.of(openaiAdapter, compatAdapter));

        // request.model() = null(第 4 位),key.getModel() = "deepseek-chat"
        AiChatRequest req = new AiChatRequest(1L, List.of(new ChatMessage("user", "hi")), null, null, null, null);
        service.chat(req, 42L).collectList().block();

        var captor = org.mockito.ArgumentCaptor.forClass(LlmStreamRequest.class);
        verify(compatAdapter).stream(captor.capture());
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
        when(compatAdapter.stream(any())).thenReturn(Flux.just("x"));
        service = new AiChatService(keyService, crudService, List.of(openaiAdapter, compatAdapter));

        // request.model() = "" (空串),key.getModel() = "deepseek-chat" → isBlank 视空串为未传,fallback key model
        AiChatRequest req = new AiChatRequest(1L, List.of(new ChatMessage("user", "hi")), null, "", null, null);
        service.chat(req, 42L).collectList().block();

        var captor = org.mockito.ArgumentCaptor.forClass(LlmStreamRequest.class);
        verify(compatAdapter).stream(captor.capture());
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
        when(compatAdapter.stream(any())).thenReturn(Flux.error(new LlmProviderException(0, "model is required")));
        service = new AiChatService(keyService, crudService, List.of(openaiAdapter, compatAdapter));

        AiChatRequest req = new AiChatRequest(1L, List.of(new ChatMessage("user", "hi")), null, null, null, null);
        List<ServerSentEvent<String>> events =
                service.chat(req, 42L).collectList().block();

        assertNotNull(events);
        // chat() 透传 null → adapter 报 LlmProviderException(0) → onErrorResume 转 SSE error
        assertEquals("error", events.get(0).event());
        var captor = org.mockito.ArgumentCaptor.forClass(LlmStreamRequest.class);
        verify(compatAdapter).stream(captor.capture());
        assertNull(captor.getValue().model());
    }

    // ---------- testConnection ----------

    @Test
    void testConnection_success_returnsOk() {
        LlmApiKey key = key(1L, LlmProvider.OPENAI, null);
        when(keyService.getOwned(1L, 42L)).thenReturn(key);
        when(keyService.decryptSecret(key)).thenReturn("sk");
        when(openaiAdapter.stream(any())).thenReturn(Flux.just("hi"));

        AiChatService.LlmConnectionTestResult result = service.testConnection(1L, "gpt-5.6", 42L);

        assertTrue(result.success());
        assertEquals("ok", result.message());
    }

    @Test
    void testConnection_providerError_returnsSanitized() {
        LlmApiKey key = key(1L, LlmProvider.OPENAI, null);
        when(keyService.getOwned(1L, 42L)).thenReturn(key);
        when(keyService.decryptSecret(key)).thenReturn("sk");
        when(openaiAdapter.stream(any())).thenReturn(Flux.error(new LlmProviderException(401, "invalid key")));

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
        when(openaiAdapter.stream(any())).thenReturn(Flux.error(new java.util.concurrent.TimeoutException("test")));

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
        when(openaiAdapter.stream(any())).thenReturn(Flux.just("ok"));

        AiChatRequest req = new AiChatRequest(1L, List.of(new ChatMessage("user", "hi")), null, null, null, null);
        service.chat(req, 42L).collectList().block();

        var captor = org.mockito.ArgumentCaptor.forClass(LlmStreamRequest.class);
        verify(openaiAdapter).stream(captor.capture());
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
}
