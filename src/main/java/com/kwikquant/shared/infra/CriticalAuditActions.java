package com.kwikquant.shared.infra;

import java.util.Set;

public final class CriticalAuditActions {

    /** MCP {@code emergency_stop} 工具的审计 action 名。多个模块引用，抽为常量避免拼写漂移。 */
    public static final String EMERGENCY_STOP = "EMERGENCY_STOP";

    public static final Set<String> CRITICAL_ACTIONS = Set.of(
            "RISK_BYPASSED",
            "RISK_REJECTED",
            "RISK_POLICY_CREATED",
            "RISK_POLICY_UPDATED",
            "RISK_POLICY_TOGGLED",
            "RISK_POLICY_DELETED",
            "API_KEY_CREATED",
            "API_KEY_UPDATED",
            "API_KEY_DISABLED",
            "PASSWORD_CHANGED",
            "ACCOUNT_CREATED",
            "ACCOUNT_UPDATED",
            "ACCOUNT_DELETED",
            "KEY_ROTATION",
            EMERGENCY_STOP);

    public static boolean isCritical(String action) {
        return CRITICAL_ACTIONS.contains(action);
    }

    private CriticalAuditActions() {}
}
