package com.kwikquant.strategy.application;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * AI Chat 请求。校验约束见 spec-review S-2（messages ≤100，content ≤100000，maxTokens ≤32768，
 * temperature 0.0-2.0，role 白名单）。
 *
 * @param llmKeyId 用户选择的 LLM key ID
 * @param messages 对话历史
 * @param strategyId 可选，传入时注入策略上下文为 system prompt
 * @param model 可选，如 gpt-4o；不传用 provider 默认
 * @param temperature 可选，默认 0.7
 * @param maxTokens 可选，默认 4096
 */
public record AiChatRequest(
        long llmKeyId,
        @NotNull @Size(max = 100, message = "messages too many") List<@Valid ChatMessage> messages,
        Long strategyId,
        @Size(max = 100) String model,
        @DecimalMin(value = "0.0", message = "temperature must be >= 0.0")
                @DecimalMax(value = "2.0", message = "temperature must be <= 2.0")
                Double temperature,
        @Max(value = 32768, message = "maxTokens must be <= 32768") Integer maxTokens) {

    public double temperatureOrDefault() {
        return temperature != null ? temperature : 0.7;
    }

    public int maxTokensOrDefault() {
        return maxTokens != null ? maxTokens : 4096;
    }
}
