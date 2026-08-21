package com.kwikquant.ai.application;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 自然语言风控规则解析请求。
 *
 * @param llmKeyId 用户自己的 LLM 密钥 ID(BYO;归属校验 + 解密后调 provider)
 * @param text     自然语言风控描述(如"单笔下单不超过 5000 USDT,每天最多亏 2000")
 * @param model    模型名(可选;空 → key 的 available_models 首项 → provider 默认)
 */
public record RiskPolicyParseRequest(
        @Schema(description = "LLM 密钥 ID", example = "3", requiredMode = Schema.RequiredMode.REQUIRED) @NotNull
                Long llmKeyId,
        @Schema(
                        description = "自然语言风控描述",
                        example = "单笔下单不超过 5000 USDT，每天最多亏 2000，每分钟最多下 3 单",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                @NotBlank
                @Size(max = 2000)
                String text,
        @Schema(description = "模型名（不传用密钥首选模型或 provider 默认）", example = "gpt-4o") @Size(max = 100) String model) {}
