package com.kwikquant.strategy.application;

import com.kwikquant.account.application.LlmApiKeyService;
import com.kwikquant.account.domain.LlmApiKey;
import com.kwikquant.shared.types.LlmProvider;
import com.kwikquant.strategy.domain.LlmProviderNotSupportedException;
import com.kwikquant.strategy.domain.StrategyDefinition;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * AI Chat 服务：接收用户消息 → 注入策略上下文 system prompt → 用用户自己的 LLM key 转发到 provider → SSE 流式返回。
 *
 * <p><b>Provider 适配</b>：通过 {@code List<LlmProviderAdapter>} 注入，按 {@code provider()} 索引到 EnumMap
 * （与 NotificationChannel 模式一致）。
 *
 * <p><b>SSE 错误脱敏（S-5）</b>：adapter 抛 {@link LlmProviderException} 时，按 {@link #sanitize(Throwable)}
 * 分类脱敏（401/403→"API key invalid or expired"；429→"Rate limit exceeded"；500+→"LLM provider service
 * unavailable"），不透传 provider 原始错误（避免泄露 OPENAI_COMPATIBLE 自定义 baseUrl/账户片段）。
 */
@Service
public class AiChatService {

    private static final Logger log = LoggerFactory.getLogger(AiChatService.class);

    private final LlmApiKeyService keyService;
    private final StrategyCrudService crudService;
    private final Map<LlmProvider, LlmProviderAdapter> adapters;

    public AiChatService(
            LlmApiKeyService keyService, StrategyCrudService crudService, List<LlmProviderAdapter> adapterList) {
        this.keyService = keyService;
        this.crudService = crudService;
        this.adapters = new EnumMap<>(LlmProvider.class);
        for (LlmProviderAdapter a : adapterList) {
            this.adapters.put(a.provider(), a);
        }
    }

    public Flux<ServerSentEvent<String>> chat(AiChatRequest request, long userId) {
        LlmApiKey key = keyService.getOwned(request.llmKeyId(), userId);
        LlmProviderAdapter adapter = adapters.get(key.getProvider());
        if (adapter == null) {
            // 服务端配置错误（adapter bean 未注入），非用户参数错误 → 走专属 8002 而非 3001 VALIDATION_FAILED
            throw new LlmProviderNotSupportedException(key.getProvider());
        }
        String apiSecret = keyService.decryptSecret(key);
        List<ChatMessage> messages = new ArrayList<>(request.messages());
        if (request.strategyId() != null) {
            StrategyDefinition s = crudService.getOwned(request.strategyId(), userId);
            messages.add(0, new ChatMessage("system", buildSystemPrompt(s)));
        }
        LlmStreamRequest streamReq = new LlmStreamRequest(
                apiSecret,
                key.getBaseUrl(),
                request.model(),
                messages,
                request.temperatureOrDefault(),
                request.maxTokensOrDefault());
        return adapter.stream(streamReq)
                .map(delta -> ServerSentEvent.<String>builder()
                        .event("message")
                        .data(delta)
                        .build())
                .onErrorResume(e -> {
                    // S-5: 原始 provider 错误仅记日志（不透传给用户），脱敏后发 SSE error event。
                    // 不落 e.getMessage()：provider 错误 body 可能 echo 请求（含用户误粘的 key/敏感字段）
                    // 或返回 header 片段，落日志即固化。仅记状态码 + 已脱敏分类。
                    if (e instanceof LlmProviderException lpe) {
                        log.warn("LLM provider error: status={}, category={}", lpe.httpStatus(), sanitize(e));
                    } else {
                        log.warn("LLM stream interrupted: {}", e.getClass().getSimpleName());
                    }
                    return Flux.just(sseError(sanitize(e)));
                });
    }

    private static ServerSentEvent<String> sseError(String msg) {
        return ServerSentEvent.<String>builder().event("error").data(msg).build();
    }

    static String sanitize(Throwable e) {
        if (e instanceof LlmProviderException lpe) {
            int s = lpe.httpStatus();
            if (s == 401 || s == 403) {
                return "API key invalid or expired";
            }
            if (s == 429) {
                return "Rate limit exceeded, please retry later";
            }
            if (s >= 500) {
                return "LLM provider service unavailable";
            }
        }
        return "Stream interrupted";
    }

    private static String buildSystemPrompt(StrategyDefinition s) {
        return "You are assisting with a trading strategy. Name: "
                + s.getName() + ", symbol: " + s.getSymbol() + ", exchange: " + s.getExchange()
                + ", interval: " + s.getIntervalValue() + ", parameters: " + s.getParameters()
                + ". Help the user optimize or debug this strategy.";
    }
}
