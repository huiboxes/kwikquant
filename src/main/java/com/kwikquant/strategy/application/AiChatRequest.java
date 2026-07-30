package com.kwikquant.strategy.application;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * AI Chat 请求。校验约束（messages ≤100，content ≤100000，maxTokens ≤32768，
 * temperature 0.0-2.0，role 白名单）。
 *
 * @param llmKeyId 用户选择的 LLM key ID
 * @param messages 对话历史
 * @param strategyId 可选，传入时注入策略上下文为 system prompt
 * @param model 可选，如 gpt-4o；不传用 provider 默认
 * @param temperature 可选，默认 0.7
 * @param maxTokens 可选，默认 4096
 * @param sourceCode 可选，editor 模式前端传编辑器实时 code（≤1MB）；DRAFT/PUBLISHED 模式不传，后端注入
 * @param codeSource 必填，策略代码来源（editor/draft/published），决定 sourceCode 取数路径
 */
public record AiChatRequest(
        @Schema(
                        description = "LLM 密钥 ID（用户在 LlmApiKeyController 配置的）",
                        example = "42",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                long llmKeyId,
        @Schema(description = "对话历史，≤100 条", requiredMode = Schema.RequiredMode.REQUIRED)
                @NotNull
                @Size(max = 100, message = "messages too many")
                List<@Valid ChatMessage> messages,
        @Schema(description = "策略 ID，传入时注入策略上下文为 system prompt", example = "128") Long strategyId,
        @Schema(description = "模型名，如 gpt-4o；不传用 provider 默认", example = "gpt-4o") @Size(max = 100) String model,
        @Schema(description = "温度 0.0-2.0，默认 0.7", example = "0.7")
                @DecimalMin(value = "0.0", message = "temperature must be >= 0.0")
                @DecimalMax(value = "2.0", message = "temperature must be <= 2.0")
                Double temperature,
        @Schema(description = "最大生成 token，≤32768，默认 4096", example = "4096")
                @Max(value = 32768, message = "maxTokens must be <= 32768")
                Integer maxTokens,
        @Schema(description = "策略源代码，editor 模式前端传；DRAFT/PUBLISHED 模式不传由后端注入", example = "print('x')")
                @Size(max = 1_000_000, message = "sourceCode exceeds 1MB")
                String sourceCode,
        @Schema(description = "策略代码来源", requiredMode = Schema.RequiredMode.REQUIRED) @NotNull CodeSource codeSource) {

    @AssertTrue(message = "sourceCode is required when codeSource is EDITOR")
    boolean isSourceCodeRequiredForEditor() {
        // 契约校验:editor 模式前端必须传 sourceCode(后端拿不到未保存内容)。
        // DRAFT/PUBLISHED 模式 sourceCode 由后端注入,不要求前端传。违反则 controller @Valid 阶段 400,
        // 不进 service 静默拼空串(I1:避免 LLM 基于空代码给误导建议)。
        return codeSource != CodeSource.EDITOR || (sourceCode != null && !sourceCode.isBlank());
    }

    public double temperatureOrDefault() {
        return temperature != null ? temperature : 0.7;
    }

    public int maxTokensOrDefault() {
        return maxTokens != null ? maxTokens : 4096;
    }
}
