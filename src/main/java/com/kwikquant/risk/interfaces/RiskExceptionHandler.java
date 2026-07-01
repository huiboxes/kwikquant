package com.kwikquant.risk.interfaces;

import com.kwikquant.risk.domain.RiskPolicyConflictException;
import com.kwikquant.risk.domain.RiskPolicyNotFoundException;
import com.kwikquant.risk.domain.RiskRejectedException;
import com.kwikquant.shared.infra.ApiResponse;
import com.kwikquant.shared.infra.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Risk exception handler — global ({@code @RestControllerAdvice} without basePackages) so it
 * catches {@link RiskRejectedException} thrown from the trading OrderController path (rule
 * rejection + service-unavailable rejection), not just risk-package controllers. Priority
 * {@code @Order(0)} beats {@link GlobalExceptionHandler}.
 *
 * <p>Key design decision: {@link RiskRejectedException} returns HTTP 200 — risk rejection is a
 * <em>business result</em>, not an HTTP error. The error is encoded in
 * {@link ApiResponse#code()} as {@link ErrorCode#ORDER_RISK_REJECTED}.
 */
@RestControllerAdvice
@Order(0)
public class RiskExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(RiskExceptionHandler.class);

    /**
     * Handles risk rejection — HTTP 200 with error code in body.
     *
     * <p>Risk rejection is a business outcome, not an infrastructure failure.
     * The client checks {@code ApiResponse.code} to distinguish success from rejection.
     */
    @ExceptionHandler(RiskRejectedException.class)
    @ResponseStatus(HttpStatus.OK)
    public ApiResponse<Void> handleRiskRejected(RiskRejectedException e) {
        log.info("Order {} risk-rejected: {}", e.getOrderId(), e.getReason());
        return ApiResponse.error(ErrorCode.ORDER_RISK_REJECTED, e.getReason(), traceId());
    }

    /**
     * Handles risk policy not found — HTTP 404.
     */
    @ExceptionHandler(RiskPolicyNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiResponse<Void> handlePolicyNotFound(RiskPolicyNotFoundException e) {
        return ApiResponse.error(ErrorCode.RISK_POLICY_NOT_FOUND, e.getMessage(), traceId());
    }

    /**
     * Handles duplicate risk policy creation (same account + ruleType) — HTTP 409.
     *
     * <p>{@link RiskPolicyConflictException} is thrown by {@code RiskPolicyManagementService.create}
     * when the {@code uk_risk_policies_acct_type} unique index is violated. Using a risk-specific
     * exception (rather than the generic {@code DataIntegrityViolationException}) keeps this
     * handler global without accidentally catching other modules' UK violations.
     */
    @ExceptionHandler(RiskPolicyConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiResponse<Void> handlePolicyConflict(RiskPolicyConflictException e) {
        log.warn("Risk policy conflict: accountId={} ruleType={}", e.getAccountId(), e.getRuleType());
        return ApiResponse.error(ErrorCode.RISK_POLICY_CONFLICT, "risk policy already exists for this ruleType", traceId());
    }

    private static String traceId() {
        return MDC.get("traceId");
    }
}
