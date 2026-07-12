package com.kwikquant.account.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class RefreshTokenCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenCleanupScheduler.class);

    private final RefreshTokenMapper refreshTokenMapper;

    RefreshTokenCleanupScheduler(RefreshTokenMapper refreshTokenMapper) {
        this.refreshTokenMapper = refreshTokenMapper;
    }

    @Scheduled(fixedDelay = 3600_000L, initialDelay = 300_000L)
    void cleanup() {
        int deleted = refreshTokenMapper.deleteExpiredAndRevoked();
        if (deleted > 0) {
            log.info("[auth] cleaned up {} expired/revoked refresh tokens", deleted);
        }
    }
}
