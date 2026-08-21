package com.kwikquant.ai.interfaces;

import com.kwikquant.ai.application.RiskPolicyParseRequest;
import com.kwikquant.ai.application.RiskPolicyParseService;
import com.kwikquant.shared.infra.ApiResponse;
import com.kwikquant.shared.infra.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 风控 REST API:自然语言 → 风控规则预览(解析端点)。
 *
 * <p>解析只产预览不落库;确认落库走 {@code POST /api/v1/risk/policies/apply}(risk 模块,
 * 事务原子 create-or-update)。两阶段拆分与 MCP {@code set_risk_rules} 的 preview/confirm
 * 语义一致:AI 产出必须经用户显式确认才生效。
 */
@RestController
@RequestMapping("/api/v1/ai/risk-policy")
@Tag(name = "AI 风控")
public class AiRiskPolicyController {

    private final RiskPolicyParseService parseService;

    public AiRiskPolicyController(RiskPolicyParseService parseService) {
        this.parseService = parseService;
    }

    /**
     * Parses natural-language risk intent into a structured rule preview (no persistence).
     *
     * @param req the parse request (llmKeyId + text + optional model)
     * @return preview rules validated against the same param rules as policy persistence
     */
    @PostMapping("/parse")
    @Operation(
            summary = "解析自然语言风控规则",
            description = "需 JWT 鉴权。用用户自己的 LLM key 将自然语言风控描述解析为结构化规则预览(不落库);"
                    + "确认落库走 POST /api/v1/risk/policies/apply。key 不存在/非本人 404;无法识别出规则 400（8004）;"
                    + "LLM provider 错误 502（8003）。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "400",
            description = "未能从描述中识别出风控规则（8004 AI_PARSE_FAILED）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "502",
            description = "LLM provider 错误（8003 LLM_PROVIDER_ERROR）")
    public ApiResponse<RiskPolicyParseView> parse(@RequestBody @Valid RiskPolicyParseRequest req) {
        long userId = SecurityUtils.currentUserId();
        RiskPolicyParseService.ParseResult result = parseService.parse(req, userId);
        return ApiResponse.ok(RiskPolicyParseView.from(result));
    }
}
