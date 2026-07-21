package com.kwikquant.trading.interfaces;

import com.kwikquant.shared.types.LiquidationEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * 强平事件 WebSocket 广播器。
 *
 * <p>{@link LiquidationEvent} 由 {@code ExecutionService.processLiquidation} 在事务提交后
 * (afterCommit)通过 {@code ApplicationEventPublisher.publishEvent} 发出,本类用 {@link EventListener}
 * 同步订阅并推到用户专属 topic {@code /topic/liquidations/{userId}}。
 *
 * <p>主题命名与 {@link OrderWebSocketBroadcaster}({@code /topic/orders|fills|positions/{userId}})
 * 一致:用户级 fanout,前端按 {@code userId} 订阅;positionId 放 body 而非 destination
 * (同一用户多个持仓可并发强平,destination 不按 positionId 拆)。
 *
 * <p>与 {@code OrderWebSocketBroadcaster}(ExecutionService 直接持有并调用)的调用方式不同:
 * 强平事件已走 Spring application event 链路(afterCommit publishEvent),用 @EventListener
 * 订阅更松耦合,且 broadcaster 不必注入到 ExecutionService。
 *
 * <p>故障容忍:convertAndSend 失败仅告警日志,不向上抛(避免影响 afterCommit 回调链路与
 * 后续通知/审计 listener)。WS 推送失败不影响强平事务已提交的事实。
 */
@Component
public class LiquidationWebSocketBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(LiquidationWebSocketBroadcaster.class);

    private final SimpMessagingTemplate messagingTemplate;

    public LiquidationWebSocketBroadcaster(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * 订阅 LiquidationEvent → 推 {@code /topic/liquidations/{userId}}。
     *
     * <p>同步执行(默认 @EventListener),在 publishEvent 的线程(即 ExecutionService 的 afterCommit
     * 回调线程)运行。convertAndSend 本身非阻塞(消息入 broker outbound queue),
     * 不阻塞 afterCommit 链路。
     */
    @EventListener
    public void onLiquidation(LiquidationEvent event) {
        try {
            messagingTemplate.convertAndSend("/topic/liquidations/" + event.userId(), event);
        } catch (Exception e) {
            log.warn(
                    "[ws] failed to broadcast LiquidationEvent: userId={} positionId={} orderId={}",
                    event.userId(),
                    event.positionId(),
                    event.orderId(),
                    e);
        }
    }
}
