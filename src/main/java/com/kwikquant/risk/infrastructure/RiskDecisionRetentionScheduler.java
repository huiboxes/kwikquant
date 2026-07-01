package com.kwikquant.risk.infrastructure;

import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class RiskDecisionRetentionScheduler {

    private static final Logger log = LoggerFactory.getLogger(RiskDecisionRetentionScheduler.class);

    private final RiskDecisionMapper decisionMapper;
    private final Duration retentionPeriod;

    RiskDecisionRetentionScheduler(
            RiskDecisionMapper decisionMapper,
            @Value("${kwikquant.risk.decision-retention-days:90}") int retentionDays) {
        this.decisionMapper = decisionMapper;
        this.retentionPeriod = Duration.ofDays(retentionDays);
    }

    @Scheduled(cron = "0 0 3 * * *")
    void purgeExpiredDecisions() {
        Instant cutoff = Instant.now().minus(retentionPeriod);
        int deleted = decisionMapper.deleteOlderThan(cutoff);
        if (deleted > 0) {
            log.info("[risk] purged {} risk_decisions older than {}", deleted, cutoff);
        }
    }
}
