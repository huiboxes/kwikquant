package com.kwikquant.strategy.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.kwikquant.shared.types.LlmProvider;
import com.kwikquant.strategy.application.ChatMessage;
import com.kwikquant.strategy.application.LlmProviderException;
import com.kwikquant.strategy.application.LlmStreamRequest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

/**
 * AbstractOpenAiAdapter(OpenAi/OpenAiCompatible 共用基类)onErrorMap 包装测试。
 *
 * <p>在 {@code .onErrorMap(WebClientResponseException.class, ...)} 之后串联
 * {@code .onErrorMap(WebClientRequestException.class, e -> new LlmProviderException(-1, "network: " + e.getMessage()))},
 * 把网络层异常(连接超时/被墙/DNS 失败)包装成 {@link LlmProviderException} status=-1,供 AiChatService.sanitize
 * 走"无法连接 LLM provider"分支。
 *
 * <p>测试用 {@link ExchangeFunction} 直接抛 {@link WebClientRequestException}(零真实网络,确定性),
 * 经 {@link AbstractOpenAiAdapter#AbstractOpenAiAdapter(WebClient) protected constructor} 注入 WebClient
 * (Java 21 final 实例字段 VarHandle 是 READ_ONLY 无法注入,故采用 Spring 风格 constructor injection)。
 */
class AbstractOpenAiAdapterTest {

    @Test
    void stream_whenWebClientRequestException_shouldWrapToLlmProviderExceptionMinus1() {
        // Arrange:匿名子类实例化 abstract adapter(OPENAI provider),经 protected constructor 注入 failingClient
        AbstractOpenAiAdapter adapter = new AbstractOpenAiAdapter(failingWebClient()) {
            @Override
            public LlmProvider provider() {
                return LlmProvider.OPENAI;
            }

            @Override
            protected String defaultBaseUrl() {
                return "https://api.openai.com/v1";
            }

            @Override
            protected String defaultModel() {
                return "gpt-4o";
            }
        };

        LlmStreamRequest req = new LlmStreamRequest(
                "sk-secret", "https://api.openai.com/v1", "gpt-4o", List.of(new ChatMessage("user", "hi")), 0.7, 1024);

        // Act & Assert:adapter 的 onErrorMap 应把 WebClientRequestException 包装成 LlmProviderException(-1)
        Throwable ex = assertThrows(
                LlmProviderException.class,
                () -> adapter.stream(req).collectList().block());
        assertThat(((LlmProviderException) ex).httpStatus()).isEqualTo(-1);
    }

    private static WebClient failingWebClient() {
        // ExchangeFunction 直接抛 WebClientRequestException(模拟连接被拒/被墙/DNS 失败),零真实网络确定性
        return WebClient.builder()
                .exchangeFunction(AbstractOpenAiAdapterTest::failWithRequestException)
                .build();
    }

    private static Mono<ClientResponse> failWithRequestException(ClientRequest request) {
        return Mono.error(new WebClientRequestException(
                new RuntimeException("conn refused"), request.method(), request.url(), request.headers()));
    }

    /**
     * L2:验证 adapter onErrorMap 把 WebClientResponseException(HTTP 4xx/5xx)包装成
     * LlmProviderException(status, body),status 透传供 AiChatService.sanitize 走对应分支。
     */
    @Test
    void stream_whenWebClientResponseException_shouldWrapToLlmProviderExceptionWithStatus() {
        AbstractOpenAiAdapter adapter = new AbstractOpenAiAdapter(failingWebClientResponse404()) {
            @Override
            public LlmProvider provider() {
                return LlmProvider.OPENAI;
            }

            @Override
            protected String defaultBaseUrl() {
                return "https://api.openai.com/v1";
            }

            @Override
            protected String defaultModel() {
                return "gpt-4o";
            }
        };
        LlmStreamRequest req = new LlmStreamRequest(
                "sk-secret", "https://api.openai.com/v1", "gpt-4o", List.of(new ChatMessage("user", "hi")), 0.7, 1024);

        Throwable ex = assertThrows(
                LlmProviderException.class,
                () -> adapter.stream(req).collectList().block());
        assertThat(((LlmProviderException) ex).httpStatus()).isEqualTo(404);
    }

    private static WebClient failingWebClientResponse404() {
        return WebClient.builder()
                .exchangeFunction(AbstractOpenAiAdapterTest::failWithResponse404)
                .build();
    }

    private static Mono<ClientResponse> failWithResponse404(ClientRequest request) {
        return Mono.error(WebClientResponseException.create(
                HttpStatus.NOT_FOUND.value(),
                "Not Found",
                HttpHeaders.EMPTY,
                "model not found".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8));
    }

    // ---------- adapter SSE 解析 extractContent(OpenAI delta.content) ----------

    @Test
    void stream_shouldExtractDeltaContentFromSseData() {
        AbstractOpenAiAdapter adapter = new AbstractOpenAiAdapter(sseWebClient()) {
            @Override
            public LlmProvider provider() {
                return LlmProvider.OPENAI;
            }

            @Override
            protected String defaultBaseUrl() {
                return "https://api.openai.com/v1";
            }

            @Override
            protected String defaultModel() {
                return "gpt-4o";
            }
        };
        LlmStreamRequest req = new LlmStreamRequest(
                "sk-secret", "https://api.openai.com/v1", "gpt-4o", List.of(new ChatMessage("user", "hi")), 0.7, 1024);

        List<String> chunks = adapter.stream(req).collectList().block();

        assertThat(chunks).contains("hello");
    }

    private static WebClient sseWebClient() {
        return WebClient.builder()
                .exchangeFunction(AbstractOpenAiAdapterTest::sseResponse)
                .build();
    }

    private static Mono<ClientResponse> sseResponse(ClientRequest request) {
        // OpenAI SSE 格式:data: {choices:[{delta:{content:"hello"}}]}\n\n data: [DONE]\n\n
        String sse = "data:{\"choices\":[{\"delta\":{\"content\":\"hello\"}}]}\n\n" + "data:[DONE]\n\n";
        return Mono.just(ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", "text/event-stream")
                .body(sse)
                .build());
    }

    // ---------- COMPATIBLE 缺 model/baseUrl → Flux.error(LlmProviderException(0)) ----------

    @Test
    void stream_whenCompatibleBaseUrlNull_shouldFluxErrorLlmProviderExceptionZero() {
        AbstractOpenAiAdapter adapter =
                new AbstractOpenAiAdapter(WebClient.builder().build()) {
                    @Override
                    public LlmProvider provider() {
                        return LlmProvider.OPENAI_COMPATIBLE;
                    }

                    @Override
                    protected String defaultBaseUrl() {
                        return null;
                    }

                    @Override
                    protected String defaultModel() {
                        return null;
                    }
                };
        // request.baseUrl null + defaultBaseUrl null → Flux.error(0, "baseUrl required")
        LlmStreamRequest req = new LlmStreamRequest(
                "sk-secret", null, "deepseek-chat", List.of(new ChatMessage("user", "hi")), 0.7, 1024);

        Throwable ex = assertThrows(
                LlmProviderException.class,
                () -> adapter.stream(req).collectList().block());
        assertThat(((LlmProviderException) ex).httpStatus()).isEqualTo(0);
    }

    @Test
    void stream_whenCompatibleModelNull_shouldFluxErrorLlmProviderExceptionZero() {
        AbstractOpenAiAdapter adapter =
                new AbstractOpenAiAdapter(WebClient.builder().build()) {
                    @Override
                    public LlmProvider provider() {
                        return LlmProvider.OPENAI_COMPATIBLE;
                    }

                    @Override
                    protected String defaultBaseUrl() {
                        return null;
                    }

                    @Override
                    protected String defaultModel() {
                        return null;
                    }
                };
        // request.baseUrl 非 null + request.model null + defaultModel null → Flux.error(0, "model required")
        LlmStreamRequest req = new LlmStreamRequest(
                "sk-secret", "https://api.deepseek.com/v1", null, List.of(new ChatMessage("user", "hi")), 0.7, 1024);

        Throwable ex = assertThrows(
                LlmProviderException.class,
                () -> adapter.stream(req).collectList().block());
        assertThat(((LlmProviderException) ex).httpStatus()).isEqualTo(0);
    }
}
