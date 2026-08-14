package com.kwikquant.strategy.interfaces;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.kwikquant.shared.infra.ApiResponse;
import com.kwikquant.shared.infra.WorkerTokenFilter;
import com.kwikquant.strategy.application.WorkerConfig;
import com.kwikquant.strategy.application.WorkerOrchestratorService;
import com.kwikquant.strategy.domain.WorkerConfigUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * WorkerBootstrapController 单测:mock {@link WorkerOrchestratorService}(getWorkerConfig),
 * 验证 bootstrap 端点据 WORKER_STRATEGY_ID_ATTR 取 config → WorkerBootstrapView,config 缺失/JWT 无 attr 抛 7307。
 */
class WorkerBootstrapControllerTest {

    private final WorkerOrchestratorService orchestratorService = mock(WorkerOrchestratorService.class);
    private final WorkerBootstrapController controller = new WorkerBootstrapController(orchestratorService);

    private static WorkerConfig cfg() {
        return new WorkerConfig(
                7L,
                "my-strat",
                "def on_bar(bar, ctx):\n    pass",
                "BTC/USDT",
                "OKX",
                "SPOT",
                "1h",
                "{}",
                "http://kwikquant-app:8080",
                "tok-abc",
                512,
                1,
                3600);
    }

    /** 构造带(或不带)WORKER_STRATEGY_ID_ATTR 的请求(模拟 WorkerTokenFilter 注入)。 */
    private static MockHttpServletRequest reqWithStrategyId(Long strategyId) {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/worker/bootstrap");
        if (strategyId != null) {
            req.setAttribute(WorkerTokenFilter.WORKER_STRATEGY_ID_ATTR, strategyId);
        }
        return req;
    }

    @Test
    void bootstrap_returnsView_whenConfigPresent() {
        when(orchestratorService.getWorkerConfig(7L)).thenReturn(cfg());

        ApiResponse<WorkerBootstrapView> resp = controller.bootstrap(reqWithStrategyId(7L));

        assertThat(resp.code()).isZero();
        WorkerBootstrapView view = resp.data();
        assertThat(view).isNotNull();
        assertThat(view.strategyId()).isEqualTo(7L);
        assertThat(view.sourceCode()).contains("on_bar");
        assertThat(view.symbol()).isEqualTo("BTC/USDT");
        assertThat(view.exchange()).isEqualTo("OKX");
        assertThat(view.marketType()).isEqualTo("SPOT");
        assertThat(view.intervalValue()).isEqualTo("1h");
        assertThat(view.parameters()).isEqualTo("{}");
        assertThat(view.apiBaseUrl()).isEqualTo("http://kwikquant-app:8080");
    }

    @Test
    void bootstrap_viewExcludesServiceToken() {
        // WorkerBootstrapView 不含 serviceToken(worker 已有 env token,bootstrap 不重复下发)。
        // sourceCode 是 bootstrap 核心价值(从 env 移到 HTTP,解 E2BIG + inspect 可窥)。
        when(orchestratorService.getWorkerConfig(7L)).thenReturn(cfg());

        WorkerBootstrapView view = controller.bootstrap(reqWithStrategyId(7L)).data();

        assertThat(view).isNotNull();
        assertThat(view.sourceCode()).isEqualTo("def on_bar(bar, ctx):\n    pass");
    }

    @Test
    void bootstrap_throws_whenConfigMissing() {
        // strategy 未运行/已停 → configRegistry 无此 strategyId → 7307,worker 收此 exit
        when(orchestratorService.getWorkerConfig(7L)).thenReturn(null);

        assertThatThrownBy(() -> controller.bootstrap(reqWithStrategyId(7L)))
                .isInstanceOf(WorkerConfigUnavailableException.class)
                .satisfies(e -> assertThat(((WorkerConfigUnavailableException) e).strategyId())
                        .isEqualTo(7L));
    }

    @Test
    void bootstrap_throws_whenNoWorkerAttr() {
        // JWT 用户(无 worker attr,未经 WorkerTokenFilter)不应调 bootstrap → 7307(strategyId=-1 标记)
        assertThatThrownBy(() -> controller.bootstrap(reqWithStrategyId(null)))
                .isInstanceOf(WorkerConfigUnavailableException.class);
    }
}
