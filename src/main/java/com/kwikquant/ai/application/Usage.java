package com.kwikquant.ai.application;

/**
 * 一次 SSE usage 帧提取出的 token 计数。OpenAI 末帧同时含 prompt+completion;Anthropic 跨两帧
 * ({@code message_start.input_tokens} / {@code message_delta.output_tokens}),每次只返一项、
 * 另一项为 0,由调用方的 {@link UsageSink} 累加。
 */
public record Usage(int promptTokens, int completionTokens) {}
