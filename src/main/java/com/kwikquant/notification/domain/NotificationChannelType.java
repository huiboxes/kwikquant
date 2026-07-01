package com.kwikquant.notification.domain;

/**
 * Supported notification delivery channels.
 *
 * <p>V1 supports only {@link #WEBSOCKET}; future versions may add EMAIL, SMS, etc.
 */
public enum NotificationChannelType {
    WEBSOCKET
}
