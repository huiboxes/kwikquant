package com.kwikquant.ai.interfaces;

import com.kwikquant.ai.application.RiskPolicyParseService;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;

/**
 * 自然语言风控解析结果视图(预览,未落库)。确认落库走 {@code POST /api/v1/risk/policies/apply}。
 *
 * @param summary 一句话复述用户意图(LLM 产出,长度截断;可能为空串)
 * @param rules   已通过落库口径校验的规则预览列表
 */
public record RiskPolicyParseView(
        @Schema(description = "一句话复述用户意图", example = "单笔不超过 5000 USDT，当日最多亏 2000") String summary,
        @Schema(description = "规则预览列表（已按落库口径校验）") List<ParsedRiskRuleView> rules) {

    /** Projects the application-layer parse result to a REST view. */
    public static RiskPolicyParseView from(RiskPolicyParseService.ParseResult result) {
        return new RiskPolicyParseView(
                result.summary(),
                result.rules().stream()
                        .map(r -> new ParsedRiskRuleView(r.ruleType().name(), r.name(), r.params()))
                        .toList());
    }

    /**
     * 单条规则预览。
     *
     * @param ruleType 规则类型枚举名(MAX_NOTIONAL / ORDER_FREQUENCY / DAILY_LOSS_LIMIT / MAX_INITIAL_MARGIN)
     * @param name     简短中文名(LLM 产出,缺省兜底)
     * @param params   规则参数(字符串 KV,与落库契约一致)
     */
    public record ParsedRiskRuleView(
            @Schema(description = "规则类型", example = "MAX_NOTIONAL") String ruleType,
            @Schema(description = "策略名称", example = "单笔名义额上限") String name,
            @Schema(description = "规则参数", example = "{\"maxNotionalUsdt\":\"5000\"}") Map<String, String> params) {}
}
