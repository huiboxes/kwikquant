package com.kwikquant.strategy.application;

import com.kwikquant.shared.types.LlmProvider;
import reactor.core.publisher.Flux;

/**
 * LLM Provider 适配器 SPI。三个实现（OpenAi/Anthropic/OpenAiCompatible）在 infrastructure 层，
 * 用 Spring WebClient 发送 streaming 请求，解析 SSE 事件流提取 content delta。
 */
public interface LlmProviderAdapter {

    LlmProvider provider();

    /** 流式返回 content delta。错误抛 {@link LlmProviderException}（含 HTTP 状态码供脱敏）。 */
    Flux<String> stream(LlmStreamRequest request);
}
