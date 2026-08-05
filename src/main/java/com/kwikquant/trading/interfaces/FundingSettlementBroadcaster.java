package com.kwikquant.trading.interfaces;

import com.kwikquant.shared.types.FundingSettlementEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * 资金费率结算事件 WebSocket 广播器。
 *
 * <p>{@link FundingSettlementEvent} 由 {@code FundingSettlementService.processFundingBill}
 * 在事务提交后(afterCommit)通过 {@code ApplicationEventPublisher.publishEvent} 发出,
 * 本类用 {@link EventListener} 同步订阅并推到用户专属 topic {@code /topic/funding/{userId}}。
 *
 * <p>主题命名仿 {@link LiquidationWebSocketBroadcaster}({@code /topic/liquidations/{userId}}):
 * 用户级 fanout,前端按 {@code userId} 订阅。
 *
 * <p>故障容忍:convertAndSend 失败仅告警日志,不向上抛(避免影响 afterCommit 回调链路与
 * 后续通知/审计 listener)。WS 推送失败不影响资金费率结算事务已提交的事实。
 */
@Component
public class FundingSettlementBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(FundingSettlementBroadcaster.class);

    private final SimpMessagingTemplate messagingTemplate;

    public FundingSettlementBroadcaster(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * 订阅 FundingSettlementEvent → 推 {@code /topic/funding/{userId}}。
     *
     * <p>同步执行(默认 @EventListener),在 publishEvent 的线程(即 FundingSettlementService
     * 的 afterCommit 回调线程)运行。convertAndSend 本身非阻塞(消息入 broker outbound queue),
     * 不阻塞 afterCommit 链路。
     */
    @EventListener
    public void onFundingSettlement(FundingSettlementEvent event) {
        try {
            messagingTemplate.convertAndSend("/topic/funding/" + event.userId(), event);
        } catch (Exception e) {
            log.warn(
                    "[ws] failed to broadcast FundingSettlementEvent: userId={} accountId={} billId={}",
                    event.userId(),
                    event.accountId(),
                    event.billId(),
                    e);
        }
    }
}
