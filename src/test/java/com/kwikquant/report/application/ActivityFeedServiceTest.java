package com.kwikquant.report.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kwikquant.report.interfaces.ActivityFeedItemDto;
import com.kwikquant.shared.infra.AuditEntry;
import com.kwikquant.shared.infra.AuditRepository;
import com.kwikquant.shared.types.ActivityCreatedEvent;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.databind.ObjectMapper;

/**
 * ActivityFeedService 单测(#26 JaCoCo 预存债)。
 *
 * <p>覆盖 onActivityCreated(persist + save 失败 catch) + getFeed(jdbcTemplate.query RowMapper
 * metadata parse title/subtitle + parse 失败 catch title="" subtitle=null)。
 */
class ActivityFeedServiceTest {

    private final AuditRepository auditRepository = mock(AuditRepository.class);
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ActivityFeedService service =
            new ActivityFeedService(auditRepository, jdbcTemplate, objectMapper);

    @Test
    void onActivityCreated_persistsAuditEntryWithMetadata() {
        ActivityCreatedEvent event =
                new ActivityCreatedEvent(42L, "ORDER_CREATED", "BUY 0.42 BTC", "filled", Instant.now());

        service.onActivityCreated(event);

        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditRepository).save(captor.capture());
        AuditEntry entry = captor.getValue();
        assertThat(entry.action()).isEqualTo("ORDER_CREATED");
        assertThat(entry.actorUserId()).isEqualTo("42");
        assertThat(entry.status()).isEqualTo(AuditEntry.STATUS_SUCCESS);
    }

    @Test
    void onActivityCreated_saveThrows_swallowsExceptionNoPropagate() {
        ActivityCreatedEvent event =
                new ActivityCreatedEvent(42L, "ORDER_CREATED", "title", null, Instant.now());
        org.mockito.Mockito.doThrow(new RuntimeException("db down")).when(auditRepository).save(any());

        // 不抛(catch → log.warn,Listener 不应崩)
        service.onActivityCreated(event);
    }

    @Test
    void getFeed_parsesMetadataTitleAndSubtitle() throws Exception {
        when(jdbcTemplate.query(any(String.class), any(RowMapper.class), eq(42L), eq(10)))
                .thenAnswer(inv -> {
                    RowMapper<ActivityFeedItemDto> rm = inv.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getString("action")).thenReturn("ORDER_CREATED");
                    when(rs.getTimestamp("created_at"))
                            .thenReturn(Timestamp.from(Instant.parse("2026-07-01T00:00:00Z")));
                    when(rs.getString("metadata"))
                            .thenReturn("{\"title\":\"BUY 0.42 BTC\",\"subtitle\":\"filled\"}");
                    return List.of(rm.mapRow(rs, 0));
                });

        List<ActivityFeedItemDto> feed = service.getFeed(42L, 10);

        assertThat(feed).hasSize(1);
        assertThat(feed.get(0).type()).isEqualTo("ORDER_CREATED");
        assertThat(feed.get(0).title()).isEqualTo("BUY 0.42 BTC");
        assertThat(feed.get(0).subtitle()).isEqualTo("filled");
    }

    @Test
    void getFeed_metadataParseFail_returnsEmptyTitleNullSubtitle() throws Exception {
        when(jdbcTemplate.query(any(String.class), any(RowMapper.class), eq(42L), eq(10)))
                .thenAnswer(inv -> {
                    RowMapper<ActivityFeedItemDto> rm = inv.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getString("action")).thenReturn("ORDER_CREATED");
                    when(rs.getTimestamp("created_at"))
                            .thenReturn(Timestamp.from(Instant.parse("2026-07-01T00:00:00Z")));
                    when(rs.getString("metadata")).thenReturn("not-valid-json"); // parse fail → catch
                    return List.of(rm.mapRow(rs, 0));
                });

        List<ActivityFeedItemDto> feed = service.getFeed(42L, 10);

        assertThat(feed).hasSize(1);
        assertThat(feed.get(0).type()).isEqualTo("ORDER_CREATED");
        assertThat(feed.get(0).title()).isEmpty(); // catch → 默认 ""
        assertThat(feed.get(0).subtitle()).isNull();
    }
}
