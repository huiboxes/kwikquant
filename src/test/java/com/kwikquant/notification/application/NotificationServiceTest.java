package com.kwikquant.notification.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.kwikquant.AbstractIntegrationTest;
import com.kwikquant.KwikquantApplication;
import com.kwikquant.notification.domain.NotificationChannelType;
import com.kwikquant.notification.domain.NotificationEventType;
import com.kwikquant.notification.domain.NotificationPreference;
import com.kwikquant.notification.infrastructure.NotificationPreferenceMapper;
import com.kwikquant.shared.types.AccountId;
import com.kwikquant.shared.types.OrderId;
import com.kwikquant.shared.types.RiskTriggeredEvent;
import java.time.Instant;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Integration tests for {@link NotificationService}.
 *
 * <p>Loads the full Spring context against PostgreSQL (Testcontainers) so the real
 * {@link NotificationPreferenceMapper} and {@link WebSocketNotificationChannel} beans are wired,
 * while {@link SimpMessagingTemplate} is replaced by a Mockito mock to assert push behavior.
 *
 * <p>A synchronous {@link AsyncConfigurer} forces {@code @Async} listeners to run on the test
 * thread, making assertions deterministic (no Awaitility needed). Direct method calls bypass
 * {@code @TransactionalEventListener} (only consulted on event publication after commit), so
 * the listener body is exercised synchronously here.
 *
 * <p>Covers tech-design §3.4.7 scenarios 1 (WebSocket enabled → push), 2 (no preferences →
 * default WebSocket), and §3.4.6 catch-all (channel failure must not propagate).
 */
@SpringBootTest(classes = KwikquantApplication.class)
@TestPropertySource(
        properties = {
            "JWT_SECRET=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
            "ENCRYPTION_KEY=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
        })
@Import(NotificationServiceTest.SyncAsyncConfig.class)
class NotificationServiceTest extends AbstractIntegrationTest {

    @Autowired
    NotificationService notificationService;

    @Autowired
    NotificationPreferenceMapper preferenceMapper;

    @MockitoBean
    SimpMessagingTemplate messagingTemplate;

    /** Runs all {@code @Async} methods on the calling thread for deterministic assertions. */
    @TestConfiguration
    static class SyncAsyncConfig implements AsyncConfigurer {
        @Override
        public Executor getAsyncExecutor() {
            return Runnable::run;
        }
    }

    private static long uniqueUserId() {
        return System.nanoTime() % 1_000_000L + 1_000_000L;
    }

    private static RiskTriggeredEvent riskEvent(long userId) {
        return new RiskTriggeredEvent(
                userId, new OrderId(100L), new AccountId(1L), null, "MAX_NOTIONAL triggered", Instant.now());
    }

    private void enablePref(long userId, NotificationEventType eventType, boolean enabled) {
        NotificationPreference pref = new NotificationPreference();
        pref.setUserId(userId);
        pref.setEventType(eventType);
        pref.setChannelType(NotificationChannelType.WEBSOCKET);
        pref.setEnabled(enabled);
        preferenceMapper.upsert(pref);
    }

    @Test
    void onRiskTriggered_whenWebSocketEnabled_pushes() {
        long userId = uniqueUserId();
        enablePref(userId, NotificationEventType.RISK_REJECTED, true);

        notificationService.onRiskTriggered(riskEvent(userId));

        verify(messagingTemplate).convertAndSend(eq("/topic/notifications/" + userId), any(Object.class));
    }

    @Test
    void onRiskTriggered_whenNoPreferences_defaultsWebSocket() {
        long userId = uniqueUserId();
        // No preferences configured → §3.4.7 scenario 2: WEBSOCKET defaults to enabled

        notificationService.onRiskTriggered(riskEvent(userId));

        verify(messagingTemplate).convertAndSend(eq("/topic/notifications/" + userId), any(Object.class));
    }

    @Test
    void onRiskTriggered_whenChannelFails_doesNotPropagate() {
        long userId = uniqueUserId();
        enablePref(userId, NotificationEventType.RISK_REJECTED, true);
        doThrow(new RuntimeException("simulated broker outage"))
                .when(messagingTemplate)
                .convertAndSend(anyString(), any(Object.class));

        // §3.4.6 catch-all: WebSocketNotificationChannel.send swallows the failure internally
        // and NotificationService.onRiskTriggered has an outer try/catch — neither may propagate
        assertThatCode(() -> notificationService.onRiskTriggered(riskEvent(userId)))
                .doesNotThrowAnyException();
    }

    @Test
    void onRiskTriggered_whenWebSocketDisabled_doesNotPush() {
        long userId = uniqueUserId();
        // P2-4: user explicitly disabled WEBSOCKET — must NOT fall back to the default.
        enablePref(userId, NotificationEventType.RISK_REJECTED, false);

        notificationService.onRiskTriggered(riskEvent(userId));

        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }
}
