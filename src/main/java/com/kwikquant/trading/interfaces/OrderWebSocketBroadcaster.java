package com.kwikquant.trading.interfaces;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * WebSocket STOMP 广播器。
 *
 * <p>事务提交后由 ExecutionService 调用，向用户专属主题推送 OrderEvent / FillEvent / PositionEvent。
 * 主题格式：/topic/orders/{userId}、/topic/fills/{userId}、/topic/positions/{userId}。
 */
@Component
public class OrderWebSocketBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(OrderWebSocketBroadcaster.class);

    private final SimpMessagingTemplate messagingTemplate;

    public OrderWebSocketBroadcaster(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    public void broadcast(long userId, OrderEvent event) {
        try {
            messagingTemplate.convertAndSend("/topic/orders/" + userId, event);
        } catch (Exception e) {
            log.warn("[ws] failed to broadcast OrderEvent: userId={} orderId={}", userId, event.orderId(), e);
        }
    }

    public void broadcast(long userId, FillEvent event) {
        try {
            messagingTemplate.convertAndSend("/topic/fills/" + userId, event);
        } catch (Exception e) {
            log.warn("[ws] failed to broadcast FillEvent: userId={} fillId={}", userId, event.fillId(), e);
        }
    }

    public void broadcast(long userId, PositionEvent event) {
        try {
            messagingTemplate.convertAndSend("/topic/positions/" + userId, event);
        } catch (Exception e) {
            log.warn("[ws] failed to broadcast PositionEvent: userId={} positionId={}", userId, event.positionId(), e);
        }
    }
}
