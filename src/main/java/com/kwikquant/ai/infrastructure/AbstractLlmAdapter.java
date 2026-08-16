package com.kwikquant.ai.infrastructure;

import com.kwikquant.ai.application.LlmProperties;
import com.kwikquant.ai.application.LlmProviderAdapter;
import com.kwikquant.ai.application.LlmProviderException;
import com.kwikquant.ai.application.Usage;
import com.kwikquant.ai.application.UsageSink;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import tools.jackson.databind.ObjectMapper;

/**
 * 4 个 LLM adapter（OpenAI / Anthropic / OpenAiCompatible）共用基类。
 *
 * <p>收敛三处重复：① 共享 {@link WebClient}（替代各 adapter 各自 {@code WebClient.builder().build()}，
 * 无 timeout、连接/响应挂死无兜底）；② 共享 {@link ObjectMapper}（替代各 adapter 各自 {@code new
 * ObjectMapper()}）；③ 默认模型名经 {@link LlmProperties} 按 provider 取（替代硬编码常量）。
 *
 * <p><b>流式骨架</b>：{@link #streamSse} 把 OpenAI/Anthropic 各自重复的 SSE pipeline 收成一份——
 * {@code bodyToFlux(ServerSentEvent<String>)} → {@code mapNotNull(ServerSentEvent::data)}（过滤 null data：
 * 注释帧/ping，消灭 reactor null NPE；原 AnthropicAdapter 的 {@code .map().filter(d!=null)} 反模式
 * 在 null 元素上 filter 根本来不及执行、直接 onError NPE）→ 过滤 done 帧 → {@code map(extractContent)}
 * → 过滤空串 → 3min timeout → onErrorMap 两层只写一份（{@link WebClientResponseException}→带 status 的
 * {@link LlmProviderException}；{@link WebClientRequestException}→网络层 status=-1）。
 *
 * <p><b>职责边界</b>：baseUrl null 检查在本方法（COMPATIBLE 的 defaultBaseUrl=null 且 request.baseUrl=null
 * → {@code Flux.error(0)}）；model null 检查在各子类 {@code stream()} 开头（model 在 body 里，本方法不解析
 * body，不感知 model 字段）。
 */
abstract class AbstractLlmAdapter implements LlmProviderAdapter {

    protected final WebClient webClient;
    protected final ObjectMapper objectMapper = new ObjectMapper();
    protected final LlmProperties llmProperties;

    protected AbstractLlmAdapter(WebClient webClient, LlmProperties llmProperties) {
        this.webClient = webClient;
        this.llmProperties = llmProperties;
    }

    /** 子类提供 provider 默认 baseUrl（COMPATIBLE 返 null，强制用户传 request.baseUrl）。 */
    protected abstract String defaultBaseUrl();

    /**
     * 默认模型名按 provider 从 {@link LlmProperties} 取；无配置返 {@code null}（COMPATIBLE 未配→null
     * → 子类 stream() 报 model required）。
     */
    protected String defaultModel() {
        return llmProperties.defaultModel().get(provider());
    }

    /** SSE 请求头键值对（{@code Authorization}/{x-api-key}/{Content-Type} 等）。 */
    protected record Header(String name, String value) {}

    protected static Header header(String name, String value) {
        return new Header(name, value);
    }

    /**
     * 收敛的 SSE 流式骨架。四段 lambda 把 provider 差异隔离到子类：{@code isDoneFrame} 判定结束帧
     * （OpenAI {@code [DONE]} 独立帧 / Anthropic 结束信号在 payload 里→{@code d->false}），
     * {@code extractContent} 从 SSE data 提取 content delta，{@code extractUsage} 从每帧 SSE data 提取
     * usage（{@link Optional#empty()} 表示该帧无 usage，OpenAI 末帧 / Anthropic message_start+message_delta
     * 跨帧）。
     *
     * <p><b>usage 副产物副作用</b>：在过滤 done 帧之后、{@code map(extractContent)} 之前插
     * {@code doOnNext} 调 {@code extractUsage}，命中则调 {@code usageSink.accept}。try-catch 包裹：
     * usage 解析异常被吞，绝不中断 content 主流程（usage 是计费副产物，content 是用户可见主流程）。
     */
    protected Flux<String> streamSse(
            String baseUrl,
            String path,
            List<Header> headers,
            Object body,
            Predicate<String> isDoneFrame,
            Function<String, String> extractContent,
            Function<String, Optional<Usage>> extractUsage,
            UsageSink usageSink) {
        if (baseUrl == null) {
            return Flux.error(new LlmProviderException(0, "baseUrl required for " + provider()));
        }
        return webClient
                .post()
                .uri(baseUrl + path)
                .headers(h -> headers.forEach(hdr -> h.add(hdr.name(), hdr.value())))
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
                // mapNotNull(非 map):OpenRouter 等 provider SSE 有注释行(: OPENROUTER PROCESSING)
                // 和空 data frame,ServerSentEvent::data 返 null。reactor 不允许 Flux null 元素,
                // .map 下游 filter 会在调 predicate 前抛 NullPointerException(实测踩坑)。
                // mapNotNull 自动过滤 null 元素,等价于 map + filter(d!=null) 但不触发 reactor null 约束。
                .mapNotNull(ServerSentEvent::data)
                .filter(d -> !isDoneFrame.test(d))
                // usage 副产物:从每帧 SSE data 提取 usage(OpenAI 末帧 / Anthropic 跨两帧),命中调 sink。
                // try-catch 防 usage 解析异常中断 content 流(usage 是次要副产物,content 是主流程)。
                .doOnNext(d -> {
                    try {
                        Optional<Usage> u = extractUsage.apply(d);
                        u.ifPresent(usage -> usageSink.accept(usage.promptTokens(), usage.completionTokens()));
                    } catch (Exception e) {
                        // usage 提取失败不影响 content 流(usage 是次要副产物)
                    }
                })
                .map(extractContent)
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
}
