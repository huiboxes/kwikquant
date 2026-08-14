package com.kwikquant.ai.infrastructure;

import com.kwikquant.ai.application.LlmProperties;
import com.kwikquant.ai.application.LlmProviderException;
import com.kwikquant.ai.application.LlmStreamRequest;
import com.kwikquant.ai.application.Usage;
import com.kwikquant.ai.application.UsageSink;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import tools.jackson.databind.JsonNode;

/**
 * OpenAI 协议 adapter 基类（{@link OpenAiAdapter} / {@link OpenAiCompatibleAdapter} 共用）。
 *
 * <p>SSE pipeline、{@link WebClient}、{@link tools.jackson.databind.ObjectMapper}、默认模型读取均收敛到
 * {@link AbstractLlmAdapter}。本类只负责 OpenAI 协议差异：路径 {@code /chat/completions}、
 * {@code Authorization: Bearer} 头、结束帧 {@code [DONE]}、{@code choices[0].delta.content} 提取。
 *
 * <p><b>可测性</b>：{@link #AbstractOpenAiAdapter(WebClient, LlmProperties) protected constructor} 注入
 * WebClient + LlmProperties，测试用 {@code WebClient.builder().exchangeFunction(...)} 注入 failing/sse
 * WebClient，零真实网络、确定性。
 */
abstract class AbstractOpenAiAdapter extends AbstractLlmAdapter {

    protected AbstractOpenAiAdapter(WebClient webClient, LlmProperties llmProperties) {
        super(webClient, llmProperties);
    }

    @Override
    public Flux<String> stream(LlmStreamRequest request, UsageSink usageSink) {
        String baseUrl = request.baseUrl() != null ? request.baseUrl() : defaultBaseUrl();
        String model = request.model() != null ? request.model() : defaultModel();
        if (model == null) {
            return Flux.error(new LlmProviderException(0, "model is required for " + provider()));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", request.messages());
        body.put("stream", true);
        body.put("stream_options", Map.of("include_usage", true)); // 让 OpenAI/COMPATIBLE 末帧返 usage
        body.put("temperature", request.temperature());
        body.put("max_tokens", request.maxTokens());
        return streamSse(
                baseUrl,
                "/chat/completions",
                List.of(
                        header("Authorization", "Bearer " + request.apiSecret()),
                        header("Content-Type", "application/json")),
                body,
                "[DONE]"::equals,
                this::extractContent,
                this::extractOpenAiUsage,
                usageSink);
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

    /**
     * 从 OpenAI SSE data 提取 usage(仅末帧含 {@code usage},带 {@code stream_options.include_usage})。
     * 非末帧 {@code usage} 缺失返 {@link Optional#empty()}。解析异常返 empty(由 streamSse 的
     * doOnNext try-catch 兜底,不影响 content 流)。
     */
    private Optional<Usage> extractOpenAiUsage(String sseData) {
        try {
            JsonNode usage = objectMapper.readTree(sseData).path("usage");
            if (usage.isMissingNode()) {
                return Optional.empty();
            }
            return Optional.of(new Usage(
                    usage.path("prompt_tokens").asInt(0),
                    usage.path("completion_tokens").asInt(0)));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
