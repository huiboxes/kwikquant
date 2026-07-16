package com.kwikquant.report.application;

import com.kwikquant.report.interfaces.ActivityFeedItemDto;
import com.kwikquant.shared.infra.AuditEntry;
import com.kwikquant.shared.infra.AuditRepository;
import com.kwikquant.shared.types.ActivityCreatedEvent;
import com.kwikquant.shared.types.ActivityTypes;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.databind.ObjectMapper;

@Service
public class ActivityFeedService {

    private static final Logger log = LoggerFactory.getLogger(ActivityFeedService.class);

    // SQL IN 子句从 ActivityTypes.ALL 动态构建，保证与 Listener 发布的 type 一致。
    private static final String QUERY_SQL = buildQuerySql();

    private static String buildQuerySql() {
        String inClause = ActivityTypes.ALL.stream().map(t -> "'" + t + "'").collect(Collectors.joining(", "));
        return """
                SELECT action, metadata, created_at
                FROM audit_logs
                WHERE actor_user_id = ?
                  AND action IN (%s)
                ORDER BY created_at DESC
                LIMIT ?
                """
                .formatted(inClause);
    }

    private final AuditRepository auditRepository;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ActivityFeedService(AuditRepository auditRepository, JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.auditRepository = auditRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onActivityCreated(ActivityCreatedEvent event) {
        try {
            Map<String, Object> metadata = new java.util.LinkedHashMap<>();
            metadata.put("title", event.title());
            if (event.subtitle() != null) {
                metadata.put("subtitle", event.subtitle());
            }

            AuditEntry entry = new AuditEntry(
                    String.valueOf(event.userId()),
                    event.type(),
                    "activity_feed",
                    "unknown",
                    null,
                    AuditEntry.STATUS_SUCCESS,
                    null,
                    metadata,
                    event.timestamp());

            auditRepository.save(entry);
        } catch (Exception e) {
            log.warn(
                    "[activity-feed] failed to persist event type={} userId={}: {}",
                    event.type(),
                    event.userId(),
                    e.getMessage());
        }
    }

    public List<ActivityFeedItemDto> getFeed(long userId, int limit) {
        return jdbcTemplate.query(
                QUERY_SQL,
                (rs, rowNum) -> {
                    String action = rs.getString("action");
                    Timestamp createdAt = rs.getTimestamp("created_at");
                    String metadataJson = rs.getString("metadata");

                    String title = "";
                    String subtitle = null;
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> meta = objectMapper.readValue(metadataJson, Map.class);
                        title = (String) meta.getOrDefault("title", "");
                        subtitle = (String) meta.get("subtitle");
                    } catch (Exception e) {
                        log.debug("[activity-feed] failed to parse metadata for action {}: {}", action, e.getMessage());
                    }

                    return new ActivityFeedItemDto(action, title, subtitle, createdAt.toInstant());
                },
                userId,
                limit);
    }
}
