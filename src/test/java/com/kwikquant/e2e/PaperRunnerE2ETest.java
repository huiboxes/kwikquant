package com.kwikquant.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.kwikquant.AbstractIntegrationTest;
import com.kwikquant.account.domain.ExchangeAccount;
import com.kwikquant.shared.infra.WorkerTokenService;
import com.kwikquant.trading.application.OrderRouter;
import com.kwikquant.trading.application.PaperExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * §7.1 六链路 - 模拟盘 Runner E2E:验证 Worker → Java 链路的关键 wiring:
 * WorkerTokenService.issueToken(strategyId, "RUNNER") → 后续 filter 校验 → OrderRouter.route(paperAccount)
 * → PaperExecutor。此测试关注 Java 侧编排,Worker(Python)侧由 pytest 独立覆盖(§7.3)。
 */
class PaperRunnerE2ETest extends AbstractIntegrationTest {

    @Autowired
    WorkerTokenService workerTokenService;

    @Autowired
    OrderRouter orderRouter;

    @Test
    void runnerTokenIssuance_endToEnd_taskTypeRunnerValid() {
        String token = workerTokenService.issueToken(101L, "RUNNER", 1L, "BINANCE");
        assertThat(token).isNotBlank();
        assertThat(workerTokenService.validateToken(token, 101L)).isTrue();
        assertThat(workerTokenService.getEntry(token).taskType()).isEqualTo("RUNNER");
    }

    @Test
    void orderRouter_paperAccount_routesToPaperExecutor() {
        ExchangeAccount paper = new ExchangeAccount();
        paper.setPaperTrading(true);
        assertThat(orderRouter.route(paper)).isInstanceOf(PaperExecutor.class);
    }

    @Test
    void revokeRunnerToken_afterStop_invalidatesToken() {
        String token = workerTokenService.issueToken(202L, "RUNNER", 1L, "BINANCE");
        assertThat(workerTokenService.validateToken(token, 202L)).isTrue();
        workerTokenService.revokeTokenForStrategy(202L);
        assertThat(workerTokenService.validateToken(token, 202L)).isFalse();
    }
}
