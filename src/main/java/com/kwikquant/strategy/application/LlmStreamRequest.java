package com.kwikquant.strategy.application;

import java.util.List;

/**
 * LLM Provider 流式请求（传给 {@link LlmProviderAdapter}）。
 *
 * @param apiSecret 解密后的完整 API key 明文（仅 AiChatService 内部构造，不暴露 REST）
 * @param baseUrl 自定义端点（OPENAI_COMPATIBLE 必填，OPENAI/ANTHROPIC 为 null→adapter 用默认）
 * @param model 模型名，null→adapter 用 provider 默认（gpt-4o / claude-sonnet-4-20250514）
 * @param messages 含注入的 system prompt 前缀
 * @param temperature 0.0-2.0
 * @param maxTokens ≤32768
 */
public record LlmStreamRequest(
        String apiSecret, String baseUrl, String model, List<ChatMessage> messages, double temperature, int maxTokens) {

    /**
     * 覆写 record 默认 toString，屏蔽 apiSecret 以防日志/异常路径泄漏解密后的完整 API key。
     * 保留其他字段用于诊断（baseUrl/model/temperature/maxTokens、messages 计数）。
     */
    @Override
    public String toString() {
        return "LlmStreamRequest[apiSecret=***REDACTED***, baseUrl=" + baseUrl
                + ", model=" + model
                + ", messages=" + (messages == null ? 0 : messages.size()) + " msgs"
                + ", temperature=" + temperature
                + ", maxTokens=" + maxTokens + "]";
    }
}
