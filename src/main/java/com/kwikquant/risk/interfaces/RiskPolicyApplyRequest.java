package com.kwikquant.risk.interfaces;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 批量应用风控策略请求体(自然语言风控"确认后落库"):单事务原子 create-or-update。
 *
 * @param accountId 目标账户 ID(归属校验)
 * @param rules     应用项列表(1–4 条,与 ruleType 种类上限一致)
 */
public record RiskPolicyApplyRequest(
        @Schema(description = "目标账户 ID", example = "7", requiredMode = Schema.RequiredMode.REQUIRED) long accountId,
        @Schema(description = "应用项列表（1–4 条）", requiredMode = Schema.RequiredMode.REQUIRED)
                @NotEmpty
                @Size(max = 4)
                @Valid
                List<RiskPolicyApplyItemRequest> rules) {}
