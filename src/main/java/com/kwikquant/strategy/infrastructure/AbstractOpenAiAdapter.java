package com.kwikquant.strategy.infrastructure;

import com.kwikquant.strategy.application.LlmProviderAdapter;
import com.kwikquant.strategy.application.LlmProviderException;
import com.kwikquant.strategy.application.LlmStreamRequest;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * OpenAI 协议 adapter 基类（{@link OpenAiAdapter} / {@link OpenAiCompatibleAdapter} 共用）。
 *
 * <p>用 {@link WebClient} 发送 streaming 请求，解析 SSE {@code data:} 字段提取 {@code choices[0].delta.content}，
 * 结束信号 {@code data: [DONE]}。4xx/5xx 包装为 {@link LlmProviderException}（含状态码供 AiChatService 脱敏）。
 *
 * <p>onErrorMap 串联两层包装 —— {@link WebClientResponseException}（HTTP 4xx/5xx）
 * → {@code LlmProviderException(status, body)}；{@link WebClientRequestException}（网络层：连接超时/被墙/DNS
 * 失败）→ {@code LlmProviderException(-1, "network: ...")}。status=-1 标识网络层（非 HTTP 响应），
 * 供 {@code AiChatService.sanitize} 走"无法连接 LLM provider"分支。
 *
 * <p><b>可测性</b>：提供 {@link #AbstractOpenAiAdapter(WebClient) protected constructor} 注入 WebClient，
 * 测试用 {@code WebClient.builder().exchangeFunction(...)} 直接抛 {@link WebClientRequestException}，
 * 零真实网络、确定性。生产代码默认走 {@link #AbstractOpenAiAdapter() 无参 constructor} 初始化默认 WebClient。
 */
abstract class AbstractOpenAiAdapter implements LlmProviderAdapter {

    protected final WebClient webClient;
    protected final ObjectMapper objectMapper = new ObjectMapper();

    protected AbstractOpenAiAdapter() {
        this.webClient = WebClient.builder().build();
    }

    protected AbstractOpenAiAdapter(WebClient webClient) {
        this.webClient = webClient;
    }

    protected abstract String defaultBaseUrl();

    protected abstract String defaultModel();

    @Override
    public Flux<String> stream(LlmStreamRequest request) {
        String baseUrl = request.baseUrl() != null ? request.baseUrl() : defaultBaseUrl();
        if (baseUrl == null) {
            return Flux.error(new LlmProviderException(0, "baseUrl required for OPENAI_COMPATIBLE provider"));
        }
        String model = request.model() != null ? request.model() : defaultModel();
        if (model == null) {
            return Flux.error(new LlmProviderException(0, "model is required for OPENAI_COMPATIBLE provider"));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", request.messages());
        body.put("stream", true);
        body.put("temperature", request.temperature());
        body.put("max_tokens", request.maxTokens());
        return webClient
                .post()
                .uri(baseUrl + "/chat/completions")
                .header("Authorization", "Bearer " + request.apiSecret())
                .header("Content-Type", "application/json")
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
                .map(ServerSentEvent::data)
                .filter(d -> d != null && !"[DONE]".equals(d))
                .map(this::extractContent)
                .filter(s -> !s.isEmpty())
                .timeout(Duration.ofMinutes(3))
                .onErrorMap(
                        WebClientResponseException.class,
                        e -> new LlmProviderException(e.getStatusCode().value(), e.getResponseBodyAsString()))
                .onErrorMap(
                        WebClientRequestException.class,
                        e -> new LlmProviderException(
                                -1, "network: " + e.getClass().getSimpleName()));
    }

    private String extractContent(String sseData) {
        try {
            JsonNode node = objectMapper.readTree(sseData);
            JsonNode content = node.path("choices").path(0).path("delta").path("content");
            return content.isMissingNode() ? "" : content.asText();
        } catch (Exception e) {
            throw new LlmProviderException(500, "OpenAI SSE parse error: " + e.getMessage());
        }
    }
}
