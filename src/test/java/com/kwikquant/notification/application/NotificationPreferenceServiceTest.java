package com.kwikquant.notification.application;

import static org.assertj.core.api.Assertions.*;

import com.kwikquant.AbstractIntegrationTest;
import com.kwikquant.KwikquantApplication;
import com.kwikquant.notification.application.NotificationPreferenceService.PreferenceUpdateItem;
import com.kwikquant.notification.domain.NotificationChannelType;
import com.kwikquant.notification.domain.NotificationEventType;
import com.kwikquant.notification.domain.NotificationPreference;
import com.kwikquant.notification.infrastructure.NotificationPreferenceMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * Integration tests for {@link NotificationPreferenceService}.
 *
 * <p>Covers tech-design §3.4.7 scenario 3 (upsert batch: existing row updated, new row inserted)
 * and the {@code listByUser} read path.
 */
@SpringBootTest(classes = KwikquantApplication.class)
@TestPropertySource(
        properties = {
            "JWT_SECRET=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
            "ENCRYPTION_KEY=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
        })
class NotificationPreferenceServiceTest extends AbstractIntegrationTest {

    @Autowired
    NotificationPreferenceService preferenceService;

    @Autowired
    NotificationPreferenceMapper preferenceMapper;

    private static long uniqueUserId() {
        return System.nanoTime() % 1_000_000L + 1_000_000L;
    }

    @Test
    void upsert_whenNew_inserts() {
        long userId = uniqueUserId();
        var item =
                new PreferenceUpdateItem(NotificationEventType.RISK_REJECTED, NotificationChannelType.WEBSOCKET, true);

        preferenceService.upsertPreferences(userId, List.of(item));

        List<NotificationPreference> prefs = preferenceMapper.findByUserId(userId);
        assertThat(prefs).hasSize(1);
        assertThat(prefs.getFirst().getEventType()).isEqualTo(NotificationEventType.RISK_REJECTED);
        assertThat(prefs.getFirst().getChannelType()).isEqualTo(NotificationChannelType.WEBSOCKET);
        assertThat(prefs.getFirst().isEnabled()).isTrue();
    }

    @Test
    void upsert_whenExisting_updates() {
        long userId = uniqueUserId();
        // scenario 3: pre-existing RISK_REJECTED+WEBSOCKET disabled, then upsert to enabled
        preferenceService.upsertPreferences(
                userId,
                List.of(new PreferenceUpdateItem(
                        NotificationEventType.RISK_REJECTED, NotificationChannelType.WEBSOCKET, false)));
        preferenceService.upsertPreferences(
                userId,
                List.of(new PreferenceUpdateItem(
                        NotificationEventType.RISK_REJECTED, NotificationChannelType.WEBSOCKET, true)));

        List<NotificationPreference> prefs = preferenceMapper.findByUserId(userId);
        assertThat(prefs).hasSize(1);
        assertThat(prefs.getFirst().isEnabled()).isTrue();
    }

    @Test
    void upsert_batchInsertsAndUpdatesMixed() {
        long userId = uniqueUserId();
        // pre-existing RISK_REJECTED+WEBSOCKET=false
        preferenceService.upsertPreferences(
                userId,
                List.of(new PreferenceUpdateItem(
                        NotificationEventType.RISK_REJECTED, NotificationChannelType.WEBSOCKET, false)));
        // batch: update existing to true + insert new ORDER_FILLED+WEBSOCKET=false
        preferenceService.upsertPreferences(
                userId,
                List.of(
                        new PreferenceUpdateItem(
                                NotificationEventType.RISK_REJECTED, NotificationChannelType.WEBSOCKET, true),
                        new PreferenceUpdateItem(
                                NotificationEventType.ORDER_FILLED, NotificationChannelType.WEBSOCKET, false)));

        List<NotificationPreference> prefs = preferenceMapper.findByUserId(userId);
        assertThat(prefs).hasSize(2);
        NotificationPreference risk = prefs.stream()
                .filter(p -> p.getEventType() == NotificationEventType.RISK_REJECTED)
                .findFirst()
                .orElseThrow();
        assertThat(risk.isEnabled()).isTrue();
        NotificationPreference filled = prefs.stream()
                .filter(p -> p.getEventType() == NotificationEventType.ORDER_FILLED)
                .findFirst()
                .orElseThrow();
        assertThat(filled.isEnabled()).isFalse();
    }

    @Test
    void listByUser_returnsPrefs() {
        long userId = uniqueUserId();
        preferenceService.upsertPreferences(
                userId,
                List.of(new PreferenceUpdateItem(
                        NotificationEventType.ORDER_FILLED, NotificationChannelType.WEBSOCKET, true)));

        List<NotificationPreference> result = preferenceService.listByUser(userId);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().getEventType()).isEqualTo(NotificationEventType.ORDER_FILLED);
    }
}
