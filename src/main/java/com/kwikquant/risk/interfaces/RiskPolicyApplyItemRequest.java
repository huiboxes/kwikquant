package com.kwikquant.risk.interfaces;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;

/**
 * 批量应用风控策略的单条请求项(自然语言风控确认落库)。
 *
 * @param policyId 已有策略 ID —— 传则覆盖更新该策略(忽略 ruleType);省略则新建
 * @param ruleType 规则类型(新建必填;更新时忽略,ruleType 不可变更)
 * @param name     策略名称
 * @param params   规则参数,因 ruleType 而异
 */
public record RiskPolicyApplyItemRequest(
        @Schema(description = "已有策略 ID（覆盖已有规则时传；新建省略）", example = "42") Long policyId,
        @Schema(
                        description =
                                "规则类型（枚举: MAX_NOTIONAL | ORDER_FREQUENCY | DAILY_LOSS_LIMIT | MAX_INITIAL_MARGIN）",
                        example = "MAX_NOTIONAL",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                @NotBlank
                String ruleType,
        @Schema(description = "策略名称", example = "单笔名义额上限", requiredMode = Schema.RequiredMode.REQUIRED)
                @NotBlank
                @Size(max = 128)
                String name,
        @Schema(
                        description = "规则参数，因 ruleType 而异",
                        example = "{\"maxNotionalUsdt\":\"5000\"}",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                @NotNull
                Map<String, String> params) {}
