package com.kwikquant.ai.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.kwikquant.ai.application.ChatMessage;
import com.kwikquant.ai.application.LlmProperties;
import com.kwikquant.ai.application.LlmProviderException;
import com.kwikquant.ai.application.LlmStreamRequest;
import com.kwikquant.ai.application.UsageSink;
import com.kwikquant.shared.types.LlmProvider;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

/**
 * H7 回归测试：Anthropic 要求 system prompt 作为顶层 {@code system} 字段，不能混在 messages 数组里。
 * 之前一版实现用 {@code instanceof Map} 判断 {@code request.messages()} 元素，但该类型实际始终是
 * {@link ChatMessage} record，判断永远为 false，system 消息从未被真正拆分——且因为没有测试兜底，
 * 该 bug 从 strategy 模块引入起就一直存活，2026-07-12 一次"修复"也未能让它生效。
 *
 * <p>stream onErrorMap 测试:WebClientRequestException 包装成
 * {@link LlmProviderException} status=-1。AnthropicAdapter 现与 OpenAI 系共用 {@link AbstractLlmAdapter}
 * 的 SSE pipeline(onErrorMap 两层 + mapNotNull),但 system 字段拆分 / {@code delta.text} 提取
 * / {@code x-api-key} 头仍是本类独有逻辑,故单独覆盖以达 JaCoCo ≥95% 门控。
 */
class AnthropicAdapterTest {

    private final AnthropicAdapter adapter =
            new AnthropicAdapter(WebClient.builder().build(), llmProps());

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

    // ---------- stream onErrorMap WebClientRequestException 包装 ----------

    @Test
    void stream_whenWebClientRequestException_shouldWrapToLlmProviderExceptionMinus1() {
        // 跟 AbstractOpenAiAdapterTest 对称:onErrorMap 串联 WebClientRequestException.class →
        // LlmProviderException(-1) 包装现收敛在 AbstractLlmAdapter.streamSse。经 package-private constructor
        // 注入 failingClient(Java 21 final 字段 VarHandle 无法注入,用 Spring 风格 constructor injection)。
        AnthropicAdapter adapterWithFailingClient = new AnthropicAdapter(failingWebClient(), llmProps());

        LlmStreamRequest request = new LlmStreamRequest(
                "sk-secret",
                null, // 走 defaultBaseUrl()
                null, // 走 defaultModel()(properties: ANTHROPIC→claude-sonnet-4-20250514)
                List.of(new ChatMessage("user", "hi")),
                0.7,
                1024);

        Throwable ex = assertThrows(
                LlmProviderException.class, () -> adapterWithFailingClient.stream(request, UsageSink.noop())
                        .collectList()
                        .block());
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

    // ---------- L2: stream onErrorMap WebClientResponseException 包装(对称 AbstractOpenAiAdapterTest) ----------

    @Test
    void stream_whenWebClientResponseException_shouldWrapToLlmProviderExceptionWithStatus() {
        // 跟 AbstractOpenAiAdapterTest.L2 对称:onErrorMap WebClientResponseException →
        // LlmProviderException(status, body),status 透传供 sanitize。
        AnthropicAdapter adapterWithFailingClient = new AnthropicAdapter(failingWebClientResponse401(), llmProps());

        LlmStreamRequest request =
                new LlmStreamRequest("sk-secret", null, null, List.of(new ChatMessage("user", "hi")), 0.7, 1024);

        Throwable ex = assertThrows(
                LlmProviderException.class, () -> adapterWithFailingClient.stream(request, UsageSink.noop())
                        .collectList()
                        .block());
        assertThat(((LlmProviderException) ex).httpStatus()).isEqualTo(401);
    }

    private static WebClient failingWebClientResponse401() {
        return WebClient.builder()
                .exchangeFunction(AnthropicAdapterTest::failWithResponse401)
                .build();
    }

    private static Mono<ClientResponse> failWithResponse401(ClientRequest request) {
        return Mono.error(WebClientResponseException.create(
                HttpStatus.UNAUTHORIZED.value(),
                "Unauthorized",
                HttpHeaders.EMPTY,
                "invalid key".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8));
    }

    // ---------- adapter SSE 解析 extractDelta(Anthropic delta.text) ----------

    @Test
    void stream_shouldExtractDeltaTextFromSseData() {
        AnthropicAdapter adapter = new AnthropicAdapter(sseWebClient(), llmProps());

        LlmStreamRequest request =
                new LlmStreamRequest("sk-secret", null, null, List.of(new ChatMessage("user", "hi")), 0.7, 1024);

        List<String> chunks =
                adapter.stream(request, UsageSink.noop()).collectList().block();

        assertThat(chunks).contains("hi");
    }

    private static WebClient sseWebClient() {
        return WebClient.builder()
                .exchangeFunction(AnthropicAdapterTest::sseResponse)
                .build();
    }

    private static Mono<ClientResponse> sseResponse(ClientRequest request) {
        // Anthropic SSE:event:content_block_delta + data:{type:content_block_delta,delta:{text:"hi"}}
        // extractDelta 解析 content_block_delta.delta.text;message_stop 等返 "" 被 filter 掉
        String sse = "event:content_block_delta\n"
                + "data:{\"type\":\"content_block_delta\",\"delta\":{\"text\":\"hi\"}}\n\n"
                + "event:message_stop\ndata:{\"type\":\"message_stop\"}\n\n";
        return Mono.just(ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", "text/event-stream")
                .body(sse)
                .build());
    }

    // ---------- usage 提取(Anthropic message_start + message_delta 跨两帧) ----------

    /**
     * V49:验证 adapter 从 Anthropic usage 跨两帧提取并调 sink。prompt 在
     * message_start.message.usage.input_tokens(12),completion 在 message_delta.usage.output_tokens(50),
     * 分别在不同帧,extractAnthropicUsage 每次只返一项。sink 收到两次 (12,0)+(0,50)。
     * content_block_delta 的 "hi" 正常提取,其余帧 extractDelta 返 "" 被 filter 掉。
     */
    @Test
    void stream_shouldExtractUsageAcrossTwoFrames() {
        AnthropicAdapter adapter = new AnthropicAdapter(sseWebClientWithUsage(), llmProps());
        LlmStreamRequest request =
                new LlmStreamRequest("sk-secret", null, null, List.of(new ChatMessage("user", "hi")), 0.7, 1024);
        RecordingUsageSink sink = new RecordingUsageSink();

        List<String> chunks = adapter.stream(request, sink).collectList().block();

        // content_block_delta 的 "hi" 正常提取;message_start/message_delta/message_stop 返 "" 被 filter 掉
        assertThat(chunks).contains("hi");
        // message_start → (12, 0);message_delta → (0, 50)
        assertThat(sink.calls).hasSize(2);
        assertThat(sink.calls.get(0)[0]).isEqualTo(12);
        assertThat(sink.calls.get(0)[1]).isEqualTo(0);
        assertThat(sink.calls.get(1)[0]).isEqualTo(0);
        assertThat(sink.calls.get(1)[1]).isEqualTo(50);
    }

    private static WebClient sseWebClientWithUsage() {
        return WebClient.builder()
                .exchangeFunction(AnthropicAdapterTest::sseResponseWithUsage)
                .build();
    }

    private static Mono<ClientResponse> sseResponseWithUsage(ClientRequest request) {
        // Anthropic usage 跨两帧:message_start.message.usage.input_tokens(prompt)+
        // message_delta.usage.output_tokens(completion),content_block_delta 在中间。
        String sse = "event:message_start\n"
                + "data:{\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":12,\"output_tokens\":0}}}\n\n"
                + "event:content_block_delta\n"
                + "data:{\"type\":\"content_block_delta\",\"delta\":{\"text\":\"hi\"}}\n\n"
                + "event:message_delta\n"
                + "data:{\"type\":\"message_delta\",\"usage\":{\"output_tokens\":50}}\n\n"
                + "event:message_stop\ndata:{\"type\":\"message_stop\"}\n\n";
        return Mono.just(ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", "text/event-stream")
                .body(sse)
                .build());
    }

    /** 收集 sink.accept 调用(prompt,completion)的测试 sink。CopyOnWriteArrayList 保跨线程可见性。 */
    static final class RecordingUsageSink implements UsageSink {
        final java.util.List<int[]> calls = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public void accept(int p, int c) {
            calls.add(new int[] {p, c});
        }
    }

    private static LlmProperties llmProps() {
        return new LlmProperties(Map.of(LlmProvider.ANTHROPIC, "claude-sonnet-4-20250514"));
    }
}
