package com.kwikquant.risk.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class RiskDecisionRetentionSchedulerTest {

    @Test
    void purgeExpiredDecisions_deletesOldRecords() {
        RiskDecisionMapper mapper = mock(RiskDecisionMapper.class);
        when(mapper.deleteOlderThan(any(Instant.class))).thenReturn(42);

        var scheduler = new RiskDecisionRetentionScheduler(mapper, 90);
        scheduler.purgeExpiredDecisions();

        verify(mapper).deleteOlderThan(any(Instant.class));
    }

    @Test
    void purgeExpiredDecisions_nothingToDelete() {
        RiskDecisionMapper mapper = mock(RiskDecisionMapper.class);
        when(mapper.deleteOlderThan(any(Instant.class))).thenReturn(0);

        var scheduler = new RiskDecisionRetentionScheduler(mapper, 90);
        scheduler.purgeExpiredDecisions();

        verify(mapper).deleteOlderThan(any(Instant.class));
    }

    @Test
    void retentionPeriodIsConfigurable() {
        RiskDecisionMapper mapper = mock(RiskDecisionMapper.class);
        when(mapper.deleteOlderThan(any(Instant.class))).thenReturn(5);

        var scheduler = new RiskDecisionRetentionScheduler(mapper, 30);
        scheduler.purgeExpiredDecisions();

        var captor = org.mockito.ArgumentCaptor.forClass(Instant.class);
        verify(mapper).deleteOlderThan(captor.capture());
        Instant cutoff = captor.getValue();
        assertThat(cutoff).isBefore(Instant.now().minusSeconds(29 * 86400));
        assertThat(cutoff).isAfter(Instant.now().minusSeconds(31 * 86400));
    }
}
