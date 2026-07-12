package com.kwikquant.market.infrastructure;

import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * M5: STOMP SUBSCRIBE authorization.
 *
 * <p>Gates {@code SUBSCRIBE} frames so a client may only subscribe to topics whose trailing
 * path segment matches the authenticated userId stored in the WebSocket session attributes by
 * {@code WebSocketAuthInterceptor} (key {@code "userId"}, a String). This protects every
 * user-scoped topic ({@code /topic/orders/{userId}}, {@code /topic/fills/{userId}},
 * {@code /topic/positions/{userId}}, {@code /topic/notifications/{userId}}) from
 * cross-user eavesdropping.
 *
 * <p>Registered on the clientInboundChannel by {@link WebSocketConfig}. Lives in the market
 * module alongside the STOMP configuration and intentionally depends only on the session
 * attributes map (not on any account-module type) to keep the Spring Modulith boundary clean.
 */
@Component
public class StompSubscriptionInterceptor implements ChannelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(StompSubscriptionInterceptor.class);

    private static final Set<String> USER_SCOPED_PREFIXES = Set.of(
            "/topic/orders/",
            "/topic/fills/",
            "/topic/positions/",
            "/topic/notifications/",
            "/topic/portfolio/",
            "/topic/backtests/");

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

        if (!isUserScopedTopic(destination)) {
            return message;
        }

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
        Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
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
