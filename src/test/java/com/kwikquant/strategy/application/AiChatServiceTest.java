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
        // 契约改动 E：Flux 末尾发 event:done 终止帧（区分正常结束 vs 断连）
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
        // 契约改动 E：error 路径经 onErrorResume 后也 concat done 终止帧
        assertEquals("done", events.get(1).event());
    }

    @Test
    void chat_providerError403_sanitizesToKeyInvalid() {
        // 覆盖 sanitize 里 `s == 401 || s == 403` 的第二个分支（原 spec-review S-5 要求 403 也走 key-invalid 脱敏）
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
        // 契约改动 E：error 路径经 onErrorResume 后也 concat done 终止帧
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
        // Round 4 补：AiChatRequest.temperatureOrDefault/maxTokensOrDefault 的 non-null 分支 —— 之前所有
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
}
