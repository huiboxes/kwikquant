package com.kwikquant.shared.infra;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class CriticalAuditActionsTest {

    @Test
    void riskBypassedIsCritical() {
        assertTrue(CriticalAuditActions.isCritical("RISK_BYPASSED"));
    }

    @Test
    void apiKeyCreatedIsCritical() {
        assertTrue(CriticalAuditActions.isCritical("API_KEY_CREATED"));
    }

    @Test
    void passwordChangedIsCritical() {
        assertTrue(CriticalAuditActions.isCritical("PASSWORD_CHANGED"));
    }

    @Test
    void orderSubmittedIsNotCritical() {
        assertFalse(CriticalAuditActions.isCritical("ORDER_SUBMITTED"));
    }

    @Test
    void accountOperationsAreCritical() {
        assertTrue(CriticalAuditActions.isCritical("ACCOUNT_CREATED"));
        assertTrue(CriticalAuditActions.isCritical("ACCOUNT_UPDATED"));
        assertTrue(CriticalAuditActions.isCritical("ACCOUNT_DELETED"));
    }

    @Test
    void keyRotationIsCritical() {
        assertTrue(CriticalAuditActions.isCritical("KEY_ROTATION"));
    }
}
