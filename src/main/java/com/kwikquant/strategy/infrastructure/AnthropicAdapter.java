package com.kwikquant.strategy.infrastructure;

import com.kwikquant.shared.types.LlmProvider;
import com.kwikquant.strategy.application.LlmProviderAdapter;
import com.kwikquant.strategy.application.LlmProviderException;
import com.kwikquant.strategy.application.LlmStreamRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Anthropic Claude adapter。Auth 用 {@code x-api-key} + {@code anthropic-version}，路径 {@code /messages}，
 * SSE 解析 {@code content_block_delta.delta.text}，结束信号 {@code type: message_stop}。
 */
@Component
class AnthropicAdapter implements LlmProviderAdapter {

    private static final String DEFAULT_BASE_URL = "https://api.anthropic.com/v1";
    private static final String DEFAULT_MODEL = "claude-sonnet-4-20250514";

    private final WebClient webClient = WebClient.builder().build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public LlmProvider provider() {
        return LlmProvider.ANTHROPIC;
    }

    @Override
    public Flux<String> stream(LlmStreamRequest request) {
        String baseUrl = request.baseUrl() != null ? request.baseUrl() : DEFAULT_BASE_URL;
        String model = request.model() != null ? request.model() : DEFAULT_MODEL;

        // Anthropic API 要求 system prompt 作为顶层 `system` 字段，不能放在 messages 数组里。
        // AiChatService 统一以 role=system 消息插入 messages 头部，这里拆出来。
        StringBuilder systemPrompt = new StringBuilder();
        List<Object> userMessages = new ArrayList<>();
        for (Object msg : request.messages()) {
            if (msg instanceof Map<?, ?> m && "system".equals(m.get("role"))) {
                if (!systemPrompt.isEmpty()) systemPrompt.append("\n\n");
                systemPrompt.append(m.get("content"));
            } else {
                userMessages.add(msg);
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        if (!systemPrompt.isEmpty()) {
            body.put("system", systemPrompt.toString());
        }
        body.put("messages", userMessages);
        body.put("stream", true);
        body.put("temperature", request.temperature());
        body.put("max_tokens", request.maxTokens());
        return webClient
                .post()
                .uri(baseUrl + "/messages")
                .header("x-api-key", request.apiSecret())
                .header("anthropic-version", "2023-06-01")
                .header("Content-Type", "application/json")
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
                .map(ServerSentEvent::data)
                .filter(d -> d != null)
                .map(this::extractDelta)
                .filter(s -> !s.isEmpty())
                .timeout(Duration.ofMinutes(3))
                .onErrorMap(
                        WebClientResponseException.class,
                        e -> new LlmProviderException(e.getStatusCode().value(), e.getResponseBodyAsString()));
    }

    private String extractDelta(String sseData) {
        try {
            JsonNode node = objectMapper.readTree(sseData);
            String type = node.path("type").asText();
            if ("content_block_delta".equals(type)) {
                JsonNode text = node.path("delta").path("text");
                return text.isMissingNode() ? "" : text.asText();
            }
            return ""; // message_start / content_block_start / message_stop 等 → 无 content delta
        } catch (Exception e) {
            throw new LlmProviderException(500, "Anthropic SSE parse error: " + e.getMessage());
        }
    }
}
