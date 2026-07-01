package com.kwikquant.risk.interfaces;

import static org.junit.jupiter.api.Assertions.*;

import com.kwikquant.risk.domain.RiskPolicyConflictException;
import com.kwikquant.risk.domain.RiskPolicyNotFoundException;
import com.kwikquant.risk.domain.RiskRejectedException;
import com.kwikquant.shared.infra.ApiResponse;
import com.kwikquant.shared.infra.ErrorCode;
import org.junit.jupiter.api.Test;

class RiskExceptionHandlerTest {

    private final RiskExceptionHandler handler = new RiskExceptionHandler();

    @Test
    void riskRejectedReturnsCode4105() {
        RiskRejectedException ex = new RiskRejectedException(100L, "notional exceeds limit");

        ApiResponse<Void> response = handler.handleRiskRejected(ex);

        assertEquals(ErrorCode.ORDER_RISK_REJECTED, response.code());
        assertEquals("notional exceeds limit", response.message());
        // Note: HTTP status 200 is verified by @ResponseStatus annotation,
        // not directly testable in a unit test without MockMvc
    }

    @Test
    void policyNotFoundReturnsCode2010() {
        RiskPolicyNotFoundException ex = new RiskPolicyNotFoundException(42L);

        ApiResponse<Void> response = handler.handlePolicyNotFound(ex);

        assertEquals(ErrorCode.RISK_POLICY_NOT_FOUND, response.code());
        assertTrue(response.message().contains("42"));
    }

    @Test
    void policyConflictReturnsCode2011() {
        // uk_risk_policies_acct_type UK violation → RiskPolicyConflictException (thrown by
        // RiskPolicyManagementService.create) → HTTP 409 + RISK_POLICY_CONFLICT(2011).
        RiskPolicyConflictException ex = new RiskPolicyConflictException(7L, "MAX_NOTIONAL");

        ApiResponse<Void> response = handler.handlePolicyConflict(ex);

        assertEquals(ErrorCode.RISK_POLICY_CONFLICT, response.code());
        assertTrue(response.message().contains("risk policy already exists"));
    }
}
