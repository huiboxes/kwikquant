package com.kwikquant.mcp.interfaces.view;

import java.util.Map;

/** set_risk_rules 两阶段确认预览:规则变更要素回显(policyId 空=新建,非空=更新)。 */
public record RiskRulesPreview(
        Long policyId, Long accountId, String ruleType, String name, Map<String, String> params, Boolean enabled) {}
