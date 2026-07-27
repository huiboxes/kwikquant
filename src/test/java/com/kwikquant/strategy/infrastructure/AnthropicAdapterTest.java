package com.kwikquant.strategy.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.kwikquant.strategy.application.ChatMessage;
import com.kwikquant.strategy.application.LlmProviderException;
import com.kwikquant.strategy.application.LlmStreamRequest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Mono;

/**
 * H7 回归测试：Anthropic 要求 system prompt 作为顶层 {@code system} 字段，不能混在 messages 数组里。
 * 之前一版实现用 {@code instanceof Map} 判断 {@code request.messages()} 元素，但该类型实际始终是
 * {@link ChatMessage} record，判断永远为 false，system 消息从未被真正拆分——且因为没有测试兜底，
 * 该 bug 从 strategy 模块引入起就一直存活，2026-07-12 一次"修复"也未能让它生效。
 *
 * <p>Task 2 补 stream onErrorMap 测试(tech-design §4.1):WebClientRequestException 包装成
 * {@link LlmProviderException} status=-1,跟 {@link AbstractOpenAiAdapterTest} 对称(AnthropicAdapter
 * 独立维护 webClient 字段,需单独覆盖以达 JaCoCo ≥95% 门控)。
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

    // ---------- Task 2: stream onErrorMap WebClientRequestException 包装(tech-design §4.1) ----------

    @Test
    void stream_whenWebClientRequestException_shouldWrapToLlmProviderExceptionMinus1() {
        // 跟 AbstractOpenAiAdapterTest 对称:AnthropicAdapter 独立维护 private final WebClient webClient 字段,
        // 同样 onErrorMap 串联 WebClientRequestException.class → LlmProviderException(-1) 包装。
        // 经 package-private constructor 注入 failingClient(Java 21 final 字段 VarHandle 无法注入,
        // 用 Spring 风格 constructor injection)。
        AnthropicAdapter adapterWithFailingClient = new AnthropicAdapter(failingWebClient());

        LlmStreamRequest request = new LlmStreamRequest(
                "sk-secret",
                null, // 走 DEFAULT_BASE_URL
                null, // 走 DEFAULT_MODEL
                List.of(new ChatMessage("user", "hi")),
                0.7,
                1024);

        Throwable ex = assertThrows(
                LlmProviderException.class,
                () -> adapterWithFailingClient.stream(request).collectList().block());
        assertThat(((LlmProviderException) ex).httpStatus()).isEqualTo(-1);
    }

    private static WebClient failingWebClient() {
        return WebClient.builder()
                .exchangeFunction(AnthropicAdapterTest::failWithRequestException)
                .build();
    }

    private static Mono<ClientResponse> failWithRequestException(ClientRequest request) {
        return Mono.error(new WebClientRequestException(
                new RuntimeException("conn refused"), request.method(), request.url(), request.headers()));
    }
}
