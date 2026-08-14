package com.kwikquant.market.infrastructure;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.kwikquant.market.application.MarketDataService;
import com.kwikquant.shared.infra.PortfolioSubscriptionRegistry;
import com.kwikquant.shared.infra.WorkerTokenService;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

/**
 * Unit tests for {@link StompSubscriptionInterceptor} — SUBSCRIBE 权限 gate + WS 订阅驱动 worker 生命周期。
 */
class StompSubscriptionInterceptorTest {

    private final MarketDataService marketDataService = mock(MarketDataService.class);
    private final PortfolioSubscriptionRegistry portfolioSubscriptionRegistry =
            mock(PortfolioSubscriptionRegistry.class);
    private final StompSubscriptionInterceptor interceptor =
            new StompSubscriptionInterceptor(marketDataService, portfolioSubscriptionRegistry);

    @Test
    void preSend_whenSubscribeOwnUserId_allows() {
        Message<?> msg = subscribeMessage("/topic/notifications/123", "123", "sub-0", "session-1");

        Message<?> result = interceptor.preSend(msg, null);

        assertThat(result).isSameAs(msg);
        verifyNoInteractions(marketDataService); // user-scoped 不驱动 worker
    }

    @Test
    void preSend_whenSubscribeOtherUserId_throwsAccessDenied() {
        Message<?> msg = subscribeMessage("/topic/notifications/999", "123", "sub-0", "session-1");

        assertThatThrownBy(() -> interceptor.preSend(msg, null))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("subscribe");
        verifyNoInteractions(marketDataService);
    }

    @Test
    void preSend_whenNonSubscribe_passesThrough() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setDestination("/app/trade");
        accessor.setSessionId("session-1");
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("userId", "123");
        accessor.setSessionAttributes(attrs);
        Message<?> msg = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        Message<?> result = interceptor.preSend(msg, null);

        assertThat(result).isSameAs(msg);
        verifyNoInteractions(marketDataService);
    }

    @Test
    void preSend_whenSessionAttributesNull_throwsAccessDenied() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination("/topic/notifications/123");
        accessor.setSessionId("session-1");
        Message<?> msg = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        assertThatThrownBy(() -> interceptor.preSend(msg, null)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(marketDataService);
    }

    @Test
    void preSend_whenSubscribeOrdersTopicOwnUserId_allows() {
        Message<?> msg = subscribeMessage("/topic/orders/123", "123", "sub-0", "session-1");

        Message<?> result = interceptor.preSend(msg, null);

        assertThat(result).isSameAs(msg);
        verifyNoInteractions(marketDataService);
    }

    // ===== WS 驱动 worker 生命周期(SUBSCRIBE 起 / UNSUBSCRIBE 退 / disconnect 退)=====

    @Test
    void preSend_whenSubscribeTickerTopic_drivesOnWsSubscribe() {
        Message<?> msg = subscribeMessage("/topic/ticker/OKX/SPOT/BTC-USDT", "123", "sub-0", "session-1");

        interceptor.preSend(msg, null);

        verify(marketDataService).onWsSubscribe("/topic/ticker/OKX/SPOT/BTC-USDT", "session-1");
    }

    @Test
    void preSend_whenSubscribeKlineTopic_drivesOnWsSubscribe() {
        Message<?> msg = subscribeMessage("/topic/kline/OKX/SPOT/BTC-USDT/1m", "123", "sub-1", "session-2");

        interceptor.preSend(msg, null);

        verify(marketDataService).onWsSubscribe("/topic/kline/OKX/SPOT/BTC-USDT/1m", "session-2");
    }

    @Test
    void afterSendCompletion_whenUnsubscribe_drivesOnWsUnsubscribe() {
        // 先 SUBSCRIBE 记 subId→destination,再 UNSUBSCRIBE 反查
        Message<?> sub = subscribeMessage("/topic/kline/OKX/SPOT/BTC-USDT/1m", "123", "sub-1", "session-2");
        interceptor.preSend(sub, null);
        clearInvocations(marketDataService);

        Message<?> unsub = unsubscribeMessage("sub-1", "session-2");
        interceptor.afterSendCompletion(unsub, null, true, null);

        verify(marketDataService).onWsUnsubscribe("/topic/kline/OKX/SPOT/BTC-USDT/1m", "session-2");
    }

    @Test
    void afterSendCompletion_whenUnsubscribeUnknownSubId_noOp() {
        // 未 SUBSCRIBE 过此 subId → 查不到 destination → 不调 onWsUnsubscribe
        Message<?> unsub = unsubscribeMessage("sub-9", "session-2");

        interceptor.afterSendCompletion(unsub, null, true, null);

        verifyNoInteractions(marketDataService);
    }

    @Test
    void afterSendCompletion_whenSendFailed_doesNotUnsubscribe() {
        Message<?> unsub = unsubscribeMessage("sub-1", "session-2");

        interceptor.afterSendCompletion(unsub, null, false, new RuntimeException("send failed"));

        verifyNoInteractions(marketDataService);
    }

    @Test
    void onSessionDisconnect_drivesOnWsSessionDisconnect() {
        SessionDisconnectEvent event = mock(SessionDisconnectEvent.class);
        when(event.getSessionId()).thenReturn("session-1");

        interceptor.onSessionDisconnect(event);

        verify(marketDataService).onWsSessionDisconnect("session-1");
    }

    @Test
    void preSend_whenSubscribeTickerTopicWithBacktestToken_throwsAccessDenied() {
        Message<?> msg = subscribeMessageWithWorkerTaskType(
                "/topic/ticker/OKX/SPOT/BTC-USDT", "123", "sub-0", "session-1", WorkerTokenService.TASK_TYPE_BACKTEST);
        assertThatThrownBy(() -> interceptor.preSend(msg, null))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("BACKTEST");
        verifyNoInteractions(marketDataService); // 拒绝不起 worker
    }

    @Test
    void preSend_whenSubscribeKlineTopicWithRunnerToken_allows() {
        Message<?> msg = subscribeMessageWithWorkerTaskType(
                "/topic/kline/OKX/SPOT/BTC-USDT/1m", "123", "sub-1", "session-2", WorkerTokenService.TASK_TYPE_RUNNER);
        interceptor.preSend(msg, null);
        verify(marketDataService).onWsSubscribe("/topic/kline/OKX/SPOT/BTC-USDT/1m", "session-2");
    }

    private Message<?> subscribeMessage(String destination, String authUserId, String subId, String sessionId) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination(destination);
        accessor.setSubscriptionId(subId);
        accessor.setSessionId(sessionId);
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("userId", authUserId);
        accessor.setSessionAttributes(attrs);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private Message<?> subscribeMessageWithWorkerTaskType(
            String destination, String authUserId, String subId, String sessionId, String workerTaskType) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination(destination);
        accessor.setSubscriptionId(subId);
        accessor.setSessionId(sessionId);
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("userId", authUserId);
        attrs.put("workerTaskType", workerTaskType);
        accessor.setSessionAttributes(attrs);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private Message<?> unsubscribeMessage(String subId, String sessionId) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.UNSUBSCRIBE);
        accessor.setSubscriptionId(subId);
        accessor.setSessionId(sessionId);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
