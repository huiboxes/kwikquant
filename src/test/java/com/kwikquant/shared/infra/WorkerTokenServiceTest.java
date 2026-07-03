package com.kwikquant.shared.infra;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * WorkerTokenService 单测:service token issue/validate/revoke + 同 strategyId 重发失效旧 token。
 *
 * <p>WTS 归 shared::infra(review M1 修复):trading(filter)与 strategy(BEG/WOS)都需调,放 trading 会违反
 * "strategy 不依赖 trading"。reissueForRunningStrategies 不在此层(shared 不能依赖 strategy),由
 * WorkerOrchestratorService.reconcileRunningStrategies 调 issueToken per RUNNING strategy。
 */
class WorkerTokenServiceTest {

    private final WorkerTokenService service = new WorkerTokenService();

    @Test
    void issueToken_returnsNonEmptyToken() {
        String token = service.issueToken(7L, "BACKTEST", 1L, "BINANCE");
        assertThat(token).isNotBlank();
    }

    @Test
    void validateToken_correctStrategyId_returnsTrue() {
        String token = service.issueToken(7L, "BACKTEST", 1L, "BINANCE");
        assertThat(service.validateToken(token, 7L)).isTrue();
    }

    @Test
    void validateToken_wrongStrategyId_returnsFalse() {
        String token = service.issueToken(7L, "BACKTEST", 1L, "BINANCE");
        assertThat(service.validateToken(token, 8L)).isFalse();
    }

    @Test
    void validateToken_nullOrBlankToken_returnsFalse() {
        assertThat(service.validateToken(null, 7L)).isFalse();
        assertThat(service.validateToken("", 7L)).isFalse();
        assertThat(service.validateToken("   ", 7L)).isFalse();
    }

    @Test
    void revokeToken_invalidatesToken() {
        String token = service.issueToken(7L, "RUNNER", 1L, "BINANCE");
        assertThat(service.validateToken(token, 7L)).isTrue();
        service.revokeToken(token);
        assertThat(service.validateToken(token, 7L)).isFalse();
    }

    @Test
    void issueToken_sameStrategyIdRevokesOldToken() {
        String token1 = service.issueToken(7L, "RUNNER", 1L, "BINANCE");
        String token2 = service.issueToken(7L, "RUNNER", 1L, "BINANCE");
        assertThat(token2).isNotEqualTo(token1);
        assertThat(service.validateToken(token1, 7L)).isFalse();
        assertThat(service.validateToken(token2, 7L)).isTrue();
    }

    @Test
    void revokeToken_unknownToken_isNoop() {
        service.revokeToken("nonexistent-token");
        // no exception thrown
    }

    @Test
    void revokeToken_nullToken_isNoop() {
        service.revokeToken(null);
    }

    @Test
    void revokeTokenForStrategy_invalidatesActiveToken() {
        String token = service.issueToken(9L, "RUNNER", 1L, "BINANCE");
        assertThat(service.validateToken(token, 9L)).isTrue();
        assertThat(service.revokeTokenForStrategy(9L)).isTrue();
        assertThat(service.validateToken(token, 9L)).isFalse();
    }

    @Test
    void revokeTokenForStrategy_noActiveToken_returnsFalse() {
        assertThat(service.revokeTokenForStrategy(999L)).isFalse();
    }

    @Test
    void revokeTokenForStrategy_secondCallIsIdempotent() {
        service.issueToken(11L, "RUNNER", 1L, "BINANCE");
        assertThat(service.revokeTokenForStrategy(11L)).isTrue();
        assertThat(service.revokeTokenForStrategy(11L)).isFalse();
    }

    @Test
    void getEntry_returnsTaskTypeAndStrategyId() {
        String token = service.issueToken(3L, "BACKTEST", 1L, "BINANCE");
        WorkerTokenService.WorkerTokenEntry entry = service.getEntry(token);
        assertThat(entry).isNotNull();
        assertThat(entry.strategyId()).isEqualTo(3L);
        assertThat(entry.taskType()).isEqualTo("BACKTEST");
    }

    @Test
    void getEntry_nullOrBlankReturnsNull() {
        assertThat(service.getEntry(null)).isNull();
        assertThat(service.getEntry("  ")).isNull();
    }
}
