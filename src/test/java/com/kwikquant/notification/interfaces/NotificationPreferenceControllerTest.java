package com.kwikquant.notification.interfaces;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.kwikquant.notification.application.NotificationPreferenceService;
import com.kwikquant.notification.application.NotificationPreferenceService.PreferenceUpdateItem;
import com.kwikquant.notification.domain.NotificationChannelType;
import com.kwikquant.notification.domain.NotificationEventType;
import com.kwikquant.notification.domain.NotificationPreference;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Unit tests for {@link NotificationPreferenceController} — verifies the current user id
 * is propagated to the service and that request items are parsed into domain enums.
 *
 * <p>Style mirrors {@code RiskPolicyControllerTest}: pure Mockito with
 * {@link SecurityContextHolder} primed with the principal name as the user id.
 */
class NotificationPreferenceControllerTest {

    private NotificationPreferenceService preferenceService;
    private NotificationPreferenceController controller;

    @BeforeEach
    void setUp() {
        preferenceService = mock(NotificationPreferenceService.class);
        controller = new NotificationPreferenceController(preferenceService);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("42", "x"));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void getPreferences_returnsCurrentUserPrefs() {
        when(preferenceService.listByUser(42L))
                .thenReturn(List.of(
                        pref(1L, 42L, NotificationEventType.RISK_REJECTED, NotificationChannelType.WEBSOCKET, true)));

        var response = controller.list();

        assertThat(response.data()).hasSize(1);
        assertThat(response.data().getFirst().eventType()).isEqualTo("RISK_REJECTED");
        assertThat(response.data().getFirst().channelType()).isEqualTo("WEBSOCKET");
        assertThat(response.data().getFirst().enabled()).isTrue();
        // currentUserId (42) must be propagated — preferences are user-scoped, not account-scoped
        verify(preferenceService).listByUser(42L);
    }

    @Test
    void putPreferences_upserts() {
        NotificationPreferenceRequest req = new NotificationPreferenceRequest(List.of(
                new NotificationPreferenceRequest.PreferenceItem("RISK_REJECTED", "WEBSOCKET", true),
                new NotificationPreferenceRequest.PreferenceItem("ORDER_FILLED", "WEBSOCKET", false)));
        when(preferenceService.listByUser(42L))
                .thenReturn(List.of(
                        pref(1L, 42L, NotificationEventType.RISK_REJECTED, NotificationChannelType.WEBSOCKET, true)));

        var response = controller.upsert(req);

        // Verify upsert was called with the current user id and parsed domain enums
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PreferenceUpdateItem>> captor = ArgumentCaptor.forClass(List.class);
        verify(preferenceService).upsertPreferences(eq(42L), captor.capture());
        assertThat(captor.getValue()).hasSize(2);
        assertThat(captor.getValue().get(0).eventType()).isEqualTo(NotificationEventType.RISK_REJECTED);
        assertThat(captor.getValue().get(0).channelType()).isEqualTo(NotificationChannelType.WEBSOCKET);
        assertThat(captor.getValue().get(0).enabled()).isTrue();
        assertThat(captor.getValue().get(1).eventType()).isEqualTo(NotificationEventType.ORDER_FILLED);
        assertThat(captor.getValue().get(1).enabled()).isFalse();
        // Controller returns the re-queried preferences for the current user
        assertThat(response.data()).hasSize(1);
        verify(preferenceService).listByUser(42L);
    }

    @Test
    void putPreferences_whenInvalidEventType_throwsIllegalArgument() {
        NotificationPreferenceRequest req = new NotificationPreferenceRequest(
                List.of(new NotificationPreferenceRequest.PreferenceItem("BOGUS", "WEBSOCKET", true)));

        assertThatThrownBy(() -> controller.upsert(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid event type");
    }

    @Test
    void putPreferences_whenInvalidChannelType_throwsIllegalArgument() {
        // parseEventType succeeds (RISK_REJECTED is valid), then parseChannelType("BOGUS") fails.
        NotificationPreferenceRequest req = new NotificationPreferenceRequest(
                List.of(new NotificationPreferenceRequest.PreferenceItem("RISK_REJECTED", "BOGUS", true)));

        assertThatThrownBy(() -> controller.upsert(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid channel type");
    }

    private NotificationPreference pref(
            long id,
            long userId,
            NotificationEventType eventType,
            NotificationChannelType channelType,
            boolean enabled) {
        NotificationPreference p = new NotificationPreference();
        p.setId(id);
        p.setUserId(userId);
        p.setEventType(eventType);
        p.setChannelType(channelType);
        p.setEnabled(enabled);
        return p;
    }
}
