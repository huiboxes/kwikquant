package com.kwikquant.notification.infrastructure;

import static org.assertj.core.api.Assertions.*;

import com.kwikquant.AbstractIntegrationTest;
import com.kwikquant.KwikquantApplication;
import com.kwikquant.notification.domain.NotificationChannelType;
import com.kwikquant.notification.domain.NotificationEventType;
import com.kwikquant.notification.domain.NotificationPreference;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * Integration tests for {@link NotificationPreferenceMapper} against PostgreSQL
 * (Testcontainers). Mirrors {@code RiskPolicyMapperTest}: direct mapper exercises + upsert
 * ON CONFLICT semantics.
 */
@SpringBootTest(classes = KwikquantApplication.class)
@TestPropertySource(
        properties = {
            "JWT_SECRET=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
            "ENCRYPTION_KEY=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
        })
class NotificationPreferenceMapperTest extends AbstractIntegrationTest {

    @Autowired
    NotificationPreferenceMapper preferenceMapper;

    private static long uniqueUserId() {
        return System.nanoTime() % 1_000_000L + 1_000_000L;
    }

    private static NotificationPreference newPref(
            long userId, NotificationEventType eventType, NotificationChannelType channelType, boolean enabled) {
        NotificationPreference pref = new NotificationPreference();
        pref.setUserId(userId);
        pref.setEventType(eventType);
        pref.setChannelType(channelType);
        pref.setEnabled(enabled);
        return pref;
    }

    @Test
    void upsert_insert() {
        long userId = uniqueUserId();
        preferenceMapper.upsert(
                newPref(userId, NotificationEventType.RISK_REJECTED, NotificationChannelType.WEBSOCKET, true));

        List<NotificationPreference> loaded = preferenceMapper.findByUserId(userId);
        assertThat(loaded).hasSize(1);
        assertThat(loaded.getFirst().getUserId()).isEqualTo(userId);
        assertThat(loaded.getFirst().getEventType()).isEqualTo(NotificationEventType.RISK_REJECTED);
        assertThat(loaded.getFirst().getChannelType()).isEqualTo(NotificationChannelType.WEBSOCKET);
        assertThat(loaded.getFirst().isEnabled()).isTrue();
        assertThat(loaded.getFirst().getCreatedAt()).isNotNull();
        assertThat(loaded.getFirst().getUpdatedAt()).isNotNull();
    }

    @Test
    void upsert_onConflict_update() {
        long userId = uniqueUserId();
        // first insert: enabled=true
        preferenceMapper.upsert(
                newPref(userId, NotificationEventType.RISK_REJECTED, NotificationChannelType.WEBSOCKET, true));
        // second upsert on same (user, event, channel) triple: should UPDATE enabled to false, not insert
        preferenceMapper.upsert(
                newPref(userId, NotificationEventType.RISK_REJECTED, NotificationChannelType.WEBSOCKET, false));

        List<NotificationPreference> loaded = preferenceMapper.findByUserId(userId);
        assertThat(loaded).hasSize(1);
        assertThat(loaded.getFirst().isEnabled()).isFalse();
    }

    @Test
    void findEnabledByUserAndEventType_filtersDisabled() {
        long userId = uniqueUserId();
        preferenceMapper.upsert(
                newPref(userId, NotificationEventType.RISK_REJECTED, NotificationChannelType.WEBSOCKET, true));
        preferenceMapper.upsert(
                newPref(userId, NotificationEventType.ORDER_FILLED, NotificationChannelType.WEBSOCKET, false));

        // enabled RISK_REJECTED → returned
        List<NotificationPreference> riskEnabled =
                preferenceMapper.findEnabledByUserAndEventType(userId, "RISK_REJECTED");
        assertThat(riskEnabled).hasSize(1);
        assertThat(riskEnabled.getFirst().getEventType()).isEqualTo(NotificationEventType.RISK_REJECTED);

        // disabled ORDER_FILLED → filtered out
        assertThat(preferenceMapper.findEnabledByUserAndEventType(userId, "ORDER_FILLED"))
                .isEmpty();
        // no rows for unconfigured event type
        assertThat(preferenceMapper.findEnabledByUserAndEventType(userId, "ORDER_CANCELLED"))
                .isEmpty();
    }

    @Test
    void findByUserId_returnsAllForUser() {
        long userId = uniqueUserId();
        preferenceMapper.upsert(
                newPref(userId, NotificationEventType.RISK_REJECTED, NotificationChannelType.WEBSOCKET, true));
        preferenceMapper.upsert(
                newPref(userId, NotificationEventType.ORDER_FILLED, NotificationChannelType.WEBSOCKET, true));

        List<NotificationPreference> all = preferenceMapper.findByUserId(userId);
        assertThat(all).hasSize(2);
    }
}
