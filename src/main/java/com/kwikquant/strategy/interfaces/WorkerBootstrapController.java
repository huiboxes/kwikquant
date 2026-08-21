package com.kwikquant.strategy.interfaces;

import com.kwikquant.shared.infra.ApiResponse;
import com.kwikquant.shared.infra.WorkerTokenFilter;
import com.kwikquant.strategy.application.WorkerConfig;
import com.kwikquant.strategy.application.WorkerOrchestratorService;
import com.kwikquant.strategy.domain.WorkerConfigUnavailableException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Worker bootstrap 端点:runner 容器 {@code docker run -d} 启动后,持 RUNNER service token
 * (env {@code WORKER_SERVICE_TOKEN})GET 本端点拉取启动配置(含 sourceCode)。
 *
 * <p><b>拉取式配置下发</b>:替代 env {@code TASK_CONFIG_JSON}——sourceCode 不再进 env
 * (解 E2BIG:argv+env ~128KB 上限风险)且不裸露于 {@code docker inspect}。env 仅留引导参数
 * ({@code WORKER_SERVICE_TOKEN} + {@code KWIKQUANT_API_BASE}),完整配置(含 sourceCode)走 HTTP 拉取。
 *
 * <p><b>鉴权</b>:复用 RUNNER service token({@link WorkerTokenFilter} 放行 + 注入
 * {@code WORKER_STRATEGY_ID_ATTR}),不新增 bootstrap token——service token 已绑 strategyId,是 worker
 * 长期身份凭证(与 plan 0.x 的 confirm token 一次性凭证性质不同)。BACKTEST token 拒(回测走 stdin 下发,非 bootstrap)。
 *
 * <p><b>config 来源</b>:WOS.startContainer 时 {@code configRegistry.put(strategyId, config)}(先于
 * createAndStart,保证 worker 启动时 config 已在)。本端点 strategyId 从 token entry 取(无 path var,
 * worker 不知自己 strategyId——token 不透明)。strategy 未运行/已停 → configRegistry 无 → 7307。
 *
 * <p><b>安全模型</b>:③ 主要解 E2BIG + sourceCode 不裸露 env。RUNNER token 仍在 env(detached
 * {@code docker run -d} 容器固有限制,stdin 不工作);但泄露后需经 worker-net 访问 app 才能利用
 * (prod app 不映射宿主 8080),相比现状(sourceCode + 完整 config 直接 inspect 可见)是实质缓解。
 * 完全解决 token 可窥需 docker secrets/文件挂载,超出 1.4 范围。
 */
@RestController
@RequestMapping("/api/v1/worker")
@Tag(name = "Worker bootstrap")
class WorkerBootstrapController {

    private final WorkerOrchestratorService orchestratorService;

    WorkerBootstrapController(WorkerOrchestratorService orchestratorService) {
        this.orchestratorService = orchestratorService;
    }

    @GetMapping("/bootstrap")
    @Operation(
            summary = "Worker 拉取启动配置(Runner 通道)",
            description = "Runner 容器启动后持 X-Worker-Token(RUNNER)拉取配置 + 源码,替代 env TASK_CONFIG_JSON"
                    + "(解 sourceCode 进 env 的 E2BIG + docker inspect 可窥)。"
                    + "strategy 未运行/已停返回 404(7307 WORKER_CONFIG_UNAVAILABLE),worker exit。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "config registry 无此 strategyId(strategy 已停/重启竞态,7307 WORKER_CONFIG_UNAVAILABLE)")
    public ApiResponse<WorkerBootstrapView> bootstrap(HttpServletRequest httpReq) {
        // worker 请求必经 WorkerTokenFilter 注入 WORKER_STRATEGY_ID_ATTR;JWT 用户(无 attr)不应调 bootstrap。
        Long strategyId = (Long) httpReq.getAttribute(WorkerTokenFilter.WORKER_STRATEGY_ID_ATTR);
        if (strategyId == null) {
            throw new WorkerConfigUnavailableException(-1L);
        }
        WorkerConfig config = orchestratorService.getWorkerConfig(strategyId);
        if (config == null) {
            throw new WorkerConfigUnavailableException(strategyId);
        }
        return ApiResponse.ok(WorkerBootstrapView.from(config));
    }
}
