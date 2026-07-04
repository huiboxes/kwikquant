package com.kwikquant.mcp.interfaces.view;

import com.kwikquant.risk.domain.RiskPolicy;
import java.util.Map;

/**
 * MCP {@code get_risk_rules} / {@code set_risk_rules} 工具返回的风控规则投影。
 * {@code ruleType} 透传枚举名（MAX_NOTIONAL/DAILY_LOSS_LIMIT/ORDER_FREQUENCY）。
 */
public record RiskPolicyView(
        Long id, long accountId, String ruleType, String name, Map<String, String> params, boolean enabled) {
    public static RiskPolicyView from(RiskPolicy p) {
        return new RiskPolicyView(
                p.getId(), p.getAccountId(), p.getRuleType().name(), p.getName(), p.getParams(), p.isEnabled());
    }
}
