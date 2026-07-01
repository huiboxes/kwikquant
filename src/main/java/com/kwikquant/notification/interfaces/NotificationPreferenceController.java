package com.kwikquant.notification.interfaces;

import com.kwikquant.notification.application.NotificationPreferenceService;
import com.kwikquant.notification.application.NotificationPreferenceService.PreferenceUpdateItem;
import com.kwikquant.notification.domain.NotificationChannelType;
import com.kwikquant.notification.domain.NotificationEventType;
import com.kwikquant.notification.domain.NotificationPreference;
import com.kwikquant.shared.infra.ApiResponse;
import com.kwikquant.shared.infra.SecurityUtils;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Notification preference REST API.
 *
 * <p>All operations are scoped to the current authenticated user (resolved via
 * {@link SecurityUtils#currentUserId()}); preferences are user-scoped, not account-scoped.
 *
 * <p>Style mirrors {@code RiskPolicyController}: controller is a thin adapter that parses
 * request strings into domain enums, delegates to the service, and projects entities to DTOs.
 */
@RestController
@RequestMapping("/api/v1/notifications/preferences")
public class NotificationPreferenceController {

    private static final Logger log = LoggerFactory.getLogger(NotificationPreferenceController.class);

    private final NotificationPreferenceService preferenceService;

    public NotificationPreferenceController(NotificationPreferenceService preferenceService) {
        this.preferenceService = preferenceService;
    }

    /**
     * Lists all notification preferences for the current user.
     *
     * @return list of preference DTOs
     */
    @GetMapping
    public ApiResponse<List<NotificationPreferenceDto>> list() {
        long currentUserId = SecurityUtils.currentUserId();
        List<NotificationPreference> prefs = preferenceService.listByUser(currentUserId);
        List<NotificationPreferenceDto> dtos =
                prefs.stream().map(NotificationPreferenceDto::from).toList();
        return ApiResponse.ok(dtos);
    }

    /**
     * Batch-upserts notification preferences for the current user.
     *
     * <p>Each item is parsed into a domain enum pair and delegated to
     * {@link NotificationPreferenceService#upsertPreferences(long, List)}. Returns the
     * re-queried preference list so callers can confirm the persisted state.
     *
     * @param req the batch upsert request
     * @return the current user's preferences after the upsert
     */
    @PutMapping
    public ApiResponse<List<NotificationPreferenceDto>> upsert(@RequestBody @Valid NotificationPreferenceRequest req) {
        long currentUserId = SecurityUtils.currentUserId();
        List<PreferenceUpdateItem> items = new ArrayList<>(req.preferences().size());
        for (NotificationPreferenceRequest.PreferenceItem item : req.preferences()) {
            items.add(new PreferenceUpdateItem(
                    parseEventType(item.eventType()), parseChannelType(item.channelType()), item.enabled()));
        }
        preferenceService.upsertPreferences(currentUserId, items);
        log.info("[notification] upserted {} preference(s) for userId={}", items.size(), currentUserId);
        List<NotificationPreference> prefs = preferenceService.listByUser(currentUserId);
        List<NotificationPreferenceDto> dtos =
                prefs.stream().map(NotificationPreferenceDto::from).toList();
        return ApiResponse.ok(dtos);
    }

    private static NotificationEventType parseEventType(String eventType) {
        try {
            return NotificationEventType.valueOf(eventType.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid event type: " + eventType);
        }
    }

    private static NotificationChannelType parseChannelType(String channelType) {
        try {
            return NotificationChannelType.valueOf(channelType.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid channel type: " + channelType);
        }
    }
}
