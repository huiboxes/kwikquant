package com.kwikquant.market.infrastructure;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import com.kwikquant.market.application.MarketDataService;

/**
 * M5: STOMP SUBSCRIBE authorization + WS 订阅驱动 worker 生命周期。
 *
 * <p>双重职责:
 *
 * <ol>
 *   <li><b>权限 gate</b>(原 M5):{@code SUBSCRIBE} user-scoped topic({@code /topic/orders/{userId}} 等)时校验
 *       尾段 userId 与握手认证 userId 一致,防跨用户监听。
 *   <li><b>WS 驱动</b>(去 persistent hack):{@code SUBSCRIBE} market topic({@code /topic/ticker|/topic/kline})
 *       → {@link MarketDataService#onWsSubscribe} 起 worker(computeIfAbsent)+ wsCount++;{@code UNSUBSCRIBE}
 *       → {@link MarketDataService#onWsUnsubscribe} wsCount--,0 且非 persistent → stop;session 断开
 *       → {@link #onSessionDisconnect} 退该 session 所有 market 订阅(覆盖 runner SIGKILL:docker kill →
 *       WS session 断 → 自动退,无泄漏)。
 * </ol>
 *
 * <p><b>session→subId→destination 映射</b>:STOMP {@code UNSUBSCRIBE} 帧只带 {@code id}(subscription id)
 * 不带 {@code destination},故 SUBSCRIBE 时记录 {@code sessionId→{subId→destination}},UNSUBSCRIBE 时反查。
 * user-scoped topic 不驱动 worker,不入此映射(UNSUBSCRIBE 时查不到 destination 即 no-op)。
 *
 * <p>Registered on the clientInboundChannel by {@link WebSocketConfig}. Lives in the market module alongside
 * the STOMP configuration and depends only on {@link MarketDataService}(market.application,同模块) +
 * session attributes map(不依赖 account-module type,保持 Spring Modulith 边界)。
 */
@Component
public class StompSubscriptionInterceptor implements ChannelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(StompSubscriptionInterceptor.class);

    private static final java.util.Set<String> USER_SCOPED_PREFIXES = java.util.Set.of(
            "/topic/orders/",
            "/topic/fills/",
            "/topic/positions/",
            "/topic/notifications/",
            "/topic/portfolio/",
            "/topic/backtests/");

    private final MarketDataService marketDataService;

    /** sessionId → (subscriptionId → destination),供 UNSUBSCRIBE 反查 destination。 */
    private final ConcurrentMap<String, ConcurrentMap<String, String>> sessionSubscriptions = new ConcurrentHashMap<>();

    // @Lazy 打破构造循环:MarketDataService → SimpMessagingTemplate(broker config)→
    // WebSocketConfig → StompSubscriptionInterceptor → MarketDataService。循环是 STOMP 配置固有结构
    // (broker config 注册 interceptor,interceptor 需 service 驱动 worker,service 需 broker 的
    // messagingTemplate 推 bar),非设计缺陷;Spring 官方打破构造循环即 @Lazy(注入 proxy,延迟到运行时
    // 首次调用解析,启动时不再阻塞 bean 创建)。
    public StompSubscriptionInterceptor(@Lazy MarketDataService marketDataService) {
        this.marketDataService = marketDataService;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
        if (!StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
            return message;
        }

        String destination = accessor.getDestination();
        if (destination == null) {
            throw new AccessDeniedException("SUBSCRIBE frame missing destination");
        }

        if (isUserScopedTopic(destination)) {
            // user-scoped topic:权限 gate(不变),不驱动 worker 生命周期
            long authUserId = resolveAuthenticatedUserId(accessor);
            long targetUserId = extractTargetUserId(destination);
            if (targetUserId != authUserId) {
                log.warn(
                        "[ws] denied SUBSCRIBE: authUserId={} targetUserId={} destination={}",
                        authUserId,
                        targetUserId,
                        destination);
                throw new AccessDeniedException("Cannot subscribe to another user's topic");
            }
            return message;
        }

        // market topic(/topic/ticker|/topic/kline):WS 驱动起 worker + 记 subId→destination
        String sessionId = accessor.getSessionId();
        String subscriptionId = accessor.getSubscriptionId();
        if (sessionId != null) {
            if (subscriptionId != null) {
                sessionSubscriptions
                        .computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>())
                        .put(subscriptionId, destination);
            }
            marketDataService.onWsSubscribe(destination, sessionId);
        }
        return message;
    }

    @Override
    public void afterSendCompletion(Message<?> message, MessageChannel channel, boolean sent, Exception ex) {
        if (ex != null) {
            return; // 发送失败(如 preSend 抛 AccessDeniedException 已拒绝),不退订
        }
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
        if (!StompCommand.UNSUBSCRIBE.equals(accessor.getCommand())) {
            return;
        }
        String sessionId = accessor.getSessionId();
        String subscriptionId = accessor.getSubscriptionId();
        if (sessionId == null || subscriptionId == null) {
            return;
        }
        ConcurrentMap<String, String> subs = sessionSubscriptions.get(sessionId);
        if (subs == null) {
            return;
        }
        String destination = subs.remove(subscriptionId); // user-scoped 的 subId 不在此映射,remove 返 null → no-op
        if (subs.isEmpty()) {
            sessionSubscriptions.remove(sessionId);
        }
        if (destination != null) {
            marketDataService.onWsUnsubscribe(destination, sessionId);
        }
    }

    /**
     * WS session 断开(客户端 close / 网络断 / runner SIGKILL 后 broker 探活失败)→ 退该 session 所有
     * market 订阅(wsCount--,0 且非 persistent → stop worker)。覆盖 persistent hack 根因:runner 死
     * → WS session 断 → 自动退订,无 worker 残留。
     */
    @EventListener
    public void onSessionDisconnect(SessionDisconnectEvent event) {
        String sessionId = event.getSessionId();
        if (sessionId == null) {
            return;
        }
        sessionSubscriptions.remove(sessionId);
        marketDataService.onWsSessionDisconnect(sessionId);
    }

    private static boolean isUserScopedTopic(String destination) {
        for (String prefix : USER_SCOPED_PREFIXES) {
            if (destination.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reads the authenticated userId from the handshake session attributes. Rejects the
     * subscription when attributes are absent (unauthenticated handshake) or the userId is
     * missing/unparseable.
     */
    private static long resolveAuthenticatedUserId(StompHeaderAccessor accessor) {
        java.util.Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
        if (sessionAttributes == null) {
            throw new AccessDeniedException("WebSocket session not authenticated");
        }
        Object userIdAttr = sessionAttributes.get("userId");
        if (userIdAttr == null) {
            throw new AccessDeniedException("WebSocket session missing userId");
        }
        try {
            return Long.parseLong(userIdAttr.toString().trim());
        } catch (NumberFormatException e) {
            throw new AccessDeniedException("Invalid authenticated userId");
        }
    }

    /**
     * Extracts the trailing path segment of {@code /topic/{anything}/{userId}} and parses it
     * as a long. Rejects malformed destinations.
     */
    private static long extractTargetUserId(String destination) {
        int lastSlash = destination.lastIndexOf('/');
        if (lastSlash < 0 || lastSlash == destination.length() - 1) {
            throw new AccessDeniedException("Invalid subscription destination: " + destination);
        }
        String tail = destination.substring(lastSlash + 1);
        try {
            return Long.parseLong(tail);
        } catch (NumberFormatException e) {
            throw new AccessDeniedException("Invalid userId in destination: " + destination);
        }
    }
}
