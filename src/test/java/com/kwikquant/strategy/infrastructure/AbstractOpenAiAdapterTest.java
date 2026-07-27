package com.kwikquant.strategy.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.kwikquant.shared.types.LlmProvider;
import com.kwikquant.strategy.application.ChatMessage;
import com.kwikquant.strategy.application.LlmProviderException;
import com.kwikquant.strategy.application.LlmStreamRequest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Mono;

/**
 * AbstractOpenAiAdapter(OpenAi/OpenAiCompatible 共用基类)onErrorMap 包装测试。
 *
 * <p>tech-design §4.1:在 {@code .onErrorMap(WebClientResponseException.class, ...)} 之后串联
 * {@code .onErrorMap(WebClientRequestException.class, e -> new LlmProviderException(-1, "network: " + e.getMessage()))},
 * 把网络层异常(连接超时/被墙/DNS 失败)包装成 {@link LlmProviderException} status=-1,供 AiChatService.sanitize
 * 走"无法连接 LLM provider"分支(§4.2)。
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
}
