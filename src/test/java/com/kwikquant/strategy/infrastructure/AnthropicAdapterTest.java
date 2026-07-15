package com.kwikquant.strategy.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.kwikquant.strategy.application.ChatMessage;
import com.kwikquant.strategy.application.LlmStreamRequest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * H7 回归测试：Anthropic 要求 system prompt 作为顶层 {@code system} 字段，不能混在 messages 数组里。
 * 之前一版实现用 {@code instanceof Map} 判断 {@code request.messages()} 元素，但该类型实际始终是
 * {@link ChatMessage} record，判断永远为 false，system 消息从未被真正拆分——且因为没有测试兜底，
 * 该 bug 从 strategy 模块引入起就一直存活，2026-07-12 一次"修复"也未能让它生效。
 */
class AnthropicAdapterTest {

    private final AnthropicAdapter adapter = new AnthropicAdapter();

    @Test
    void buildRequestBody_withSystemMessage_extractsToTopLevelSystemField() {
        LlmStreamRequest request = new LlmStreamRequest(
                "secret",
                null,
                null,
                List.of(new ChatMessage("system", "策略上下文"), new ChatMessage("user", "hello")),
                0.7,
                1024);

        Map<String, Object> body = adapter.buildRequestBody(request, "claude-sonnet-4-20250514");

        assertThat(body.get("system")).isEqualTo("策略上下文");
        @SuppressWarnings("unchecked")
        List<ChatMessage> messages = (List<ChatMessage>) body.get("messages");
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).role()).isEqualTo("user");
        assertThat(messages.get(0).content()).isEqualTo("hello");
    }

    @Test
    void buildRequestBody_withMultipleSystemMessages_joinsWithBlankLine() {
        LlmStreamRequest request = new LlmStreamRequest(
                "secret",
                null,
                null,
                List.of(
                        new ChatMessage("system", "第一段"),
                        new ChatMessage("system", "第二段"),
                        new ChatMessage("user", "hi")),
                0.7,
                1024);

        Map<String, Object> body = adapter.buildRequestBody(request, "model");

        assertThat(body.get("system")).isEqualTo("第一段\n\n第二段");
    }

    @Test
    void buildRequestBody_noSystemMessage_omitsSystemKey() {
        LlmStreamRequest request =
                new LlmStreamRequest("secret", null, null, List.of(new ChatMessage("user", "hi")), 0.7, 1024);

        Map<String, Object> body = adapter.buildRequestBody(request, "model");

        assertThat(body).doesNotContainKey("system");
        @SuppressWarnings("unchecked")
        List<ChatMessage> messages = (List<ChatMessage>) body.get("messages");
        assertThat(messages).hasSize(1);
    }

    @Test
    void buildRequestBody_includesModelAndSamplingParams() {
        LlmStreamRequest request =
                new LlmStreamRequest("secret", null, null, List.of(new ChatMessage("user", "hi")), 0.5, 2048);

        Map<String, Object> body = adapter.buildRequestBody(request, "claude-sonnet-4-20250514");

        assertThat(body.get("model")).isEqualTo("claude-sonnet-4-20250514");
        assertThat(body.get("stream")).isEqualTo(true);
        assertThat(body.get("temperature")).isEqualTo(0.5);
        assertThat(body.get("max_tokens")).isEqualTo(2048);
    }
}
