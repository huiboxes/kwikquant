package com.kwikquant.notification.application;

import com.kwikquant.notification.domain.NotificationChannelType;
import com.kwikquant.notification.domain.NotificationEventType;
import com.kwikquant.notification.domain.NotificationPreference;
import com.kwikquant.notification.infrastructure.NotificationPreferenceMapper;
import com.kwikquant.shared.types.OrderStatus;
import com.kwikquant.shared.types.OrderStatusChangedEvent;
import com.kwikquant.shared.types.RiskTriggeredEvent;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Central notification dispatcher.
 *
 * <p>Listens for domain events via {@link TransactionalEventListener} (after commit)
 * and dispatches notifications to enabled channels based on user preferences.
 * If no preferences are configured, defaults to {@link NotificationChannelType#WEBSOCKET}.
 *
 * <p>All listeners are {@code @Async} to avoid blocking the publishing transaction thread.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationPreferenceMapper preferenceMapper;
    private final Map<NotificationChannelType, NotificationChannel> channelMap;

    /**
     * Constructs the service and indexes available channels by type.
     *
     * @param preferenceMapper the preference mapper
     * @param channels         all registered notification channel implementations
     */
    public NotificationService(
            NotificationPreferenceMapper preferenceMapper,
            List<NotificationChannel> channels) {
        this.preferenceMapper = preferenceMapper;

        this.channelMap = new EnumMap<>(NotificationChannelType.class);
        for (NotificationChannel channel : channels) {
            this.channelMap.put(channel.channelType(), channel);
        }
        log.info("[notification] initialized with {} channel(s): {}", channelMap.size(), channelMap.keySet());
    }

    /**
     * Handles risk rejection events by dispatching notifications to enabled channels.
     *
     * @param event the risk triggered event
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onRiskTriggered(RiskTriggeredEvent event) {
        try {
            long userId = event.userId();
            Set<NotificationChannelType> enabledChannels =
                    resolveEnabledChannels(userId, NotificationEventType.RISK_REJECTED);

            Map<String, Object> payload = Map.of(
                    "type", "RISK_REJECTED",
                    "orderId", event.orderId().value(),
                    "accountId", event.accountId().value(),
                    "reason", event.reason(),
                    "timestamp", event.timestamp().toString());

            dispatch(userId, "Risk Alert", payload, enabledChannels);
        } catch (Exception e) {
            log.warn("[notification] failed to process RiskTriggeredEvent: {}", e.getMessage(), e);
        }
    }

    /**
     * Handles order status change events by dispatching notifications for
     * terminal states (FILLED, CANCELLED).
     *
     * @param event the order status changed event
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onOrderStatusChanged(OrderStatusChangedEvent event) {
        try {
            NotificationEventType eventType = mapOrderStatus(event.newStatus());
            if (eventType == null) {
                return;
            }

            long userId = event.userId();
            Set<NotificationChannelType> enabledChannels = resolveEnabledChannels(userId, eventType);

            Map<String, Object> payload = Map.of(
                    "type", eventType.name(),
                    "orderId", event.orderId().value(),
                    "accountId", event.accountId().value(),
                    "previousStatus", event.previousStatus().name(),
                    "newStatus", event.newStatus().name(),
                    "timestamp", event.timestamp().toString());

            String title = eventType == NotificationEventType.ORDER_FILLED ? "Order Filled" : "Order Cancelled";
            dispatch(userId, title, payload, enabledChannels);
        } catch (Exception e) {
            log.warn("[notification] failed to process OrderStatusChangedEvent: {}", e.getMessage(), e);
        }
    }

    /**
     * Resolves the set of enabled notification channels for a user and event type.
     * If no preferences are configured, defaults to WEBSOCKET.
     */
    private Set<NotificationChannelType> resolveEnabledChannels(long userId, NotificationEventType eventType) {
        // Distinguish "no preference record" (apply WEBSOCKET default) from "record exists but
        // disabled" (do not push). findEnabledByUserAndEventType only returns enabled rows and
        // cannot tell the two apart, so query all rows for this event type.
        List<NotificationPreference> allPrefs =
                preferenceMapper.findByUserIdAndEventType(userId, eventType.name());

        if (allPrefs.isEmpty()) {
            return Set.of(NotificationChannelType.WEBSOCKET);
        }

        return allPrefs.stream()
                .filter(NotificationPreference::isEnabled)
                .map(NotificationPreference::getChannelType)
                .collect(Collectors.toSet());
    }

    /**
     * Dispatches a notification payload to all enabled channels.
     */
    private void dispatch(
            long userId, String title, Map<String, Object> payload, Set<NotificationChannelType> enabledChannels) {
        for (NotificationChannelType type : enabledChannels) {
            NotificationChannel channel = channelMap.get(type);
            if (channel != null) {
                channel.send(userId, title, payload);
            }
        }
    }

    /**
     * Maps an {@link OrderStatus} to the corresponding {@link NotificationEventType},
     * or returns {@code null} if the status does not warrant a notification.
     */
    private static NotificationEventType mapOrderStatus(OrderStatus status) {
        return switch (status) {
            case FILLED -> NotificationEventType.ORDER_FILLED;
            case CANCELLED -> NotificationEventType.ORDER_CANCELLED;
            default -> null;
        };
    }
}
