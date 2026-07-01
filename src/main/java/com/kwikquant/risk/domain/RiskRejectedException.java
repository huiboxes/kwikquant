package com.kwikquant.risk.domain;

/**
 * Thrown when a risk check rejects an order.
 */
public class RiskRejectedException extends RuntimeException {

    private final long orderId;
    private final String reason;

    public RiskRejectedException(long orderId, String reason) {
        super("Order " + orderId + " rejected: " + reason);
        this.orderId = orderId;
        this.reason = reason;
    }

    public long getOrderId() {
        return orderId;
    }

    public String getReason() {
        return reason;
    }
}
