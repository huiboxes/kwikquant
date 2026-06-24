package com.kwikquant.shared.infra;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

public class JdbcAuditRepository implements AuditRepository {

    private static final String INSERT_SQL =
            """
            INSERT INTO audit_logs (actor_user_id, action, target_type, target_id,
                                     trace_id, status, error, metadata, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS JSONB), ?)
            """;

    private static final long SYSTEM_ACTOR_ID = 0L;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public JdbcAuditRepository(DataSource dataSource, ObjectMapper objectMapper) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.objectMapper = objectMapper;
    }

    @Override
    public void save(AuditEntry entry) {
        long actorId = parseLong(entry.actorUserId());
        String metadataJson = serializeMetadata(entry);

        jdbcTemplate.update(
                INSERT_SQL,
                actorId,
                entry.action(),
                entry.targetType(),
                entry.targetId(),
                entry.traceId(),
                entry.status(),
                entry.error(),
                metadataJson,
                Timestamp.from(entry.createdAt()));
    }

    private String serializeMetadata(AuditEntry entry) {
        try {
            return objectMapper.writeValueAsString(entry.metadata());
        } catch (JacksonException e) {
            return "{}";
        }
    }

    private static long parseLong(String value) {
        if (value == null || value.isBlank() || "anonymous".equals(value)) {
            return SYSTEM_ACTOR_ID;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return SYSTEM_ACTOR_ID;
        }
    }
}
