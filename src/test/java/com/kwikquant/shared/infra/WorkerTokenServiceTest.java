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
        String token = service.issueBacktestToken(7L, 42L, 1L, "BINANCE");
        assertThat(token).isNotBlank();
    }

    @Test
    void validateToken_correctStrategyId_returnsTrue() {
        String token = service.issueBacktestToken(7L, 42L, 1L, "BINANCE");
        assertThat(service.validateToken(token, 7L)).isTrue();
    }

    @Test
    void validateToken_wrongStrategyId_returnsFalse() {
        String token = service.issueBacktestToken(7L, 42L, 1L, "BINANCE");
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
        String token = service.issueRunnerToken(7L, 1L, "BINANCE", 0L);
        assertThat(service.validateToken(token, 7L)).isTrue();
        service.revokeToken(token);
        assertThat(service.validateToken(token, 7L)).isFalse();
    }

    @Test
    void issueToken_sameStrategyIdRevokesOldToken() {
        String token1 = service.issueRunnerToken(7L, 1L, "BINANCE", 0L);
        String token2 = service.issueRunnerToken(7L, 1L, "BINANCE", 0L);
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
        String token = service.issueRunnerToken(9L, 1L, "BINANCE", 0L);
        assertThat(service.validateToken(token, 9L)).isTrue();
        assertThat(service.revokeRunnerTokenForStrategy(9L)).isTrue();
        assertThat(service.validateToken(token, 9L)).isFalse();
    }

    @Test
    void revokeTokenForStrategy_noActiveToken_returnsFalse() {
        assertThat(service.revokeRunnerTokenForStrategy(999L)).isFalse();
    }

    @Test
    void revokeTokenForStrategy_secondCallIsIdempotent() {
        service.issueRunnerToken(11L, 1L, "BINANCE", 0L);
        assertThat(service.revokeRunnerTokenForStrategy(11L)).isTrue();
        assertThat(service.revokeRunnerTokenForStrategy(11L)).isFalse();
    }

    @Test
    void getEntry_returnsTaskTypeAndStrategyId() {
        String token = service.issueBacktestToken(3L, 42L, 1L, "BINANCE");
        WorkerTokenService.WorkerTokenEntry entry = service.getEntry(token);
        assertThat(entry).isNotNull();
        assertThat(entry.strategyId()).isEqualTo(3L);
        assertThat(entry.taskType()).isEqualTo("BACKTEST");
        assertThat(entry.taskId()).isEqualTo(42L);
    }

    @Test
    void getEntry_nullOrBlankReturnsNull() {
        assertThat(service.getEntry(null)).isNull();
        assertThat(service.getEntry("  ")).isNull();
    }

    @Test
    void issueToken_entryCarriesUserIdAndExchange() {
        // 验证 4 参签名 userId+exchange 正确进入 entry
        String token = service.issueRunnerToken(21L, 999L, "OKX", 0L);
        WorkerTokenService.WorkerTokenEntry entry = service.getEntry(token);
        assertThat(entry.strategyId()).isEqualTo(21L);
        assertThat(entry.taskType()).isEqualTo("RUNNER");
        assertThat(entry.userId()).isEqualTo(999L);
        assertThat(entry.exchange()).isEqualTo("OKX");
        assertThat(entry.issuedAt()).isNotNull();
    }

    @Test
    void runnerAndConcurrentBacktests_haveIndependentLifecycles() {
        String runner = service.issueRunnerToken(7L, 1L, "BINANCE", 9L);
        String backtest1 = service.issueBacktestToken(7L, 101L, 1L, "BINANCE");
        String backtest2 = service.issueBacktestToken(7L, 102L, 1L, "BINANCE");

        assertThat(service.getEntry(runner)).isNotNull();
        assertThat(service.getEntry(backtest1)).isNotNull();
        assertThat(service.getEntry(backtest2)).isNotNull();

        service.revokeToken(backtest1);
        assertThat(service.getEntry(backtest1)).isNull();
        assertThat(service.getEntry(backtest2)).isNotNull();
        assertThat(service.getEntry(runner)).isNotNull();

        service.revokeRunnerTokenForStrategy(7L);
        assertThat(service.getEntry(runner)).isNull();
        assertThat(service.getEntry(backtest2)).isNotNull();
    }
}
