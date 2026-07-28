package com.kwikquant.account.infrastructure;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * RefreshTokenCleanupScheduler 单测:cleanup() 的 deleted>0 / ==0 两分支(@Scheduled 定时清理过期
 * refresh token)。
 */
class RefreshTokenCleanupSchedulerTest {

    @Test
    void cleanup_deletedGreaterThanZero_logsInfo() {
        RefreshTokenMapper mapper = Mockito.mock(RefreshTokenMapper.class);
        when(mapper.deleteExpiredAndRevoked()).thenReturn(5);
        RefreshTokenCleanupScheduler scheduler = new RefreshTokenCleanupScheduler(mapper);

        scheduler.cleanup();

        verify(mapper).deleteExpiredAndRevoked();
    }

    @Test
    void cleanup_deletedZero_doesNotLogInfo() {
        RefreshTokenMapper mapper = Mockito.mock(RefreshTokenMapper.class);
        when(mapper.deleteExpiredAndRevoked()).thenReturn(0);
        RefreshTokenCleanupScheduler scheduler = new RefreshTokenCleanupScheduler(mapper);

        scheduler.cleanup();

        verify(mapper).deleteExpiredAndRevoked();
    }
}
