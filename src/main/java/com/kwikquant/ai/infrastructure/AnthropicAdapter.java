package com.kwikquant.ai.infrastructure;

import com.kwikquant.ai.application.ChatMessage;
import com.kwikquant.ai.application.LlmProperties;
import com.kwikquant.ai.application.LlmProviderException;
import com.kwikquant.ai.application.LlmStreamRequest;
import com.kwikquant.ai.application.Usage;
import com.kwikquant.ai.application.UsageSink;
import com.kwikquant.shared.types.LlmProvider;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import tools.jackson.databind.JsonNode;

/**
 * Anthropic Claude adapter。Auth 用 {@code x-api-key} + {@code anthropic-version}，路径 {@code /messages}，
 * SSE 解析 {@code content_block_delta.delta.text}，结束信号 {@code type: message_stop}。
 *
 * <p>SSE pipeline、{@link WebClient}、{@link tools.jackson.databind.ObjectMapper}、默认模型读取均收敛到
 * {@link AbstractLlmAdapter}。本类只负责 Anthropic 协议差异：路径、{@code x-api-key} 头、
 * system 字段拆分、{@code delta.text} 提取。结束帧判定用 {@code d -> false}（Anthropic 的
 * {@code message_stop} 在 payload 里，extractDelta 返 "" 被 filter empty 过滤，不像 OpenAI 的
 * {@code [DONE]} 独立帧）。
 */
@Component
class AnthropicAdapter extends AbstractLlmAdapter {

    private static final String SYSTEM_ROLE = "system";

    AnthropicAdapter(WebClient llmWebClient, LlmProperties llmProperties) {
        super(llmWebClient, llmProperties);
    }

    @Override
    public LlmProvider provider() {
        return LlmProvider.ANTHROPIC;
    }

    @Override
    protected String defaultBaseUrl() {
        return "https://api.anthropic.com/v1";
    }

    @Override
    public Flux<String> stream(LlmStreamRequest request, UsageSink usageSink) {
        String baseUrl = request.baseUrl() != null ? request.baseUrl() : defaultBaseUrl();
        String model = request.model() != null ? request.model() : defaultModel();
        if (model == null) {
            return Flux.error(new LlmProviderException(0, "model is required for " + provider()));
        }
        Map<String, Object> body = buildRequestBody(request, model);
        return streamSse(
                baseUrl,
                "/messages",
                List.of(
                        header("x-api-key", request.apiSecret()),
                        header("anthropic-version", "2023-06-01"),
                        header("Content-Type", "application/json")),
                body,
                d -> false,
                this::extractDelta,
                this::extractAnthropicUsage,
                usageSink);
    }

    /**
     * Anthropic API 要求 system prompt 作为顶层 {@code system} 字段，不能放在 messages 数组里。
     * {@code AiChatService} 统一以 role=system 消息插入 messages 头部，这里拆出来。
     *
     * <p>抽成独立方法（而非内联在 {@link #stream}）是为了可单测：之前一版实现误判
     * {@code request.messages()} 元素类型为 {@code Map}（实际始终是 {@link ChatMessage} record），
     * 导致该判断永远为 false、system 消息从未被真正拆分，且因为没有测试兜底而未被发现。
     */
    Map<String, Object> buildRequestBody(LlmStreamRequest request, String model) {
        StringBuilder systemPrompt = new StringBuilder();
        List<ChatMessage> userMessages = new ArrayList<>();
        for (ChatMessage msg : request.messages()) {
            if (SYSTEM_ROLE.equals(msg.role())) {
                if (!systemPrompt.isEmpty()) systemPrompt.append("\n\n");
                systemPrompt.append(msg.content());
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
        return body;
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

    /**
     * 从 Anthropic SSE data 提取 usage。<b>usage 跨两帧</b>:
     * {@code message_start.message.usage.input_tokens}(prompt) 与
     * {@code message_delta.usage.output_tokens}(completion) 分别在不同帧,本方法每次只返一项、
     * 另一项为 0,由 {@code AiChatService} 的 {@code MutableUsageSink} 累加。非 usage 帧返
     * {@link Optional#empty()}。解析异常返 empty(由 streamSse 的 doOnNext try-catch 兜底)。
     */
    private Optional<Usage> extractAnthropicUsage(String sseData) {
        try {
            JsonNode node = objectMapper.readTree(sseData);
            String type = node.path("type").asText();
            if ("message_start".equals(type)) {
                int prompt =
                        node.path("message").path("usage").path("input_tokens").asInt(0);
                return prompt > 0 ? Optional.of(new Usage(prompt, 0)) : Optional.empty();
            }
            if ("message_delta".equals(type)) {
                int comp = node.path("usage").path("output_tokens").asInt(0);
                return comp > 0 ? Optional.of(new Usage(0, comp)) : Optional.empty();
            }
            return Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
