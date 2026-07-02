package com.kwikquant.notification.domain;

/**
 * Supported notification event types.
 *
 * <p>Each value maps to a class of domain events that can trigger user notifications.
 */
public enum NotificationEventType {
    RISK_REJECTED,
    ORDER_FILLED,
    ORDER_CANCELLED,
    STRATEGY_STARTED,
    STRATEGY_STOPPED,
    STRATEGY_ERROR
}
