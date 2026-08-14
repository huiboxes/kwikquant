package com.kwikquant.ai.application;

import com.kwikquant.shared.types.LlmProvider;
import reactor.core.publisher.Flux;

/**
 * LLM Provider 适配器 SPI。三个实现（OpenAi/Anthropic/OpenAiCompatible）在 infrastructure 层，
 * 用 Spring WebClient 发送 streaming 请求，解析 SSE 事件流提取 content delta。
 */
public interface LlmProviderAdapter {

    LlmProvider provider();

    /**
     * 流式返回 content delta。错误抛 {@link LlmProviderException}（含 HTTP 状态码供脱敏）。
     *
     * @param usageSink adapter 从 SSE usage 帧提取到 token 数后调此 sink(OpenAI 末帧
     *     stream_options.include_usage / Anthropic message_start+message_delta 跨帧);
     *     调用方({@code AiChatService})传可变 sink 累加,流终止落库。usage 是次要副产物,
     *     提取失败不影响 content 流(doOnNext 内 try-catch)。
     */
    Flux<String> stream(LlmStreamRequest request, UsageSink usageSink);
}
