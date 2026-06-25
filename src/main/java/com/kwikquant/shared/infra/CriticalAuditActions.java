package com.kwikquant.shared.infra;

import java.util.Set;

public final class CriticalAuditActions {

    public static final Set<String> CRITICAL_ACTIONS = Set.of(
            "RISK_BYPASSED",
            "API_KEY_CREATED",
            "API_KEY_UPDATED",
            "API_KEY_DISABLED",
            "PASSWORD_CHANGED",
            "ACCOUNT_CREATED",
            "ACCOUNT_UPDATED",
            "ACCOUNT_DELETED",
            "KEY_ROTATION");

    public static boolean isCritical(String action) {
        return CRITICAL_ACTIONS.contains(action);
    }

    private CriticalAuditActions() {}
}
