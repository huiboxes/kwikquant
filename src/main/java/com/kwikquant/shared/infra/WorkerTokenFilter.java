package com.kwikquant.shared.infra;

import com.kwikquant.shared.infra.WorkerTokenService.WorkerTokenEntry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Worker→Java REST 认证 filter(§3.2)。验证 {@code X-Worker-Token} header,对照 {@link WorkerTokenService}
 * 内存 registry,校验 taskType 与端点匹配(BACKTEST→{@code /api/v1/backtests/{taskId}/orders};
 * RUNNER→{@code /api/v1/orders}),放行后注入 strategyId 到 request attr 供下游用。
 *
 * <p>归 shared::infra(plan-外决策:SecurityConfig 在 account,trading filter 会让 account→trading 违反模块
 * 边界;与 {@code UserContextFilter} 一致,跨切安全 filter 归 shared)。由 account/SecurityConfig 装配到
 * SecurityFilterChain 的 JwtAuthenticationFilter 之前(对 Worker 端点放行后标记已认证)。
 *
 * <p>401 直接写 response(@RestControllerAdvice 捕不到 filter 异常,故不抛 WorkerTokenInvalidException,
 * 直接写 401+7301 JSON)。token 从 WTS.getEntry 取 strategyId(非 path 反查),避开跨模块调 BacktestTaskMapper。
 */
public class WorkerTokenFilter extends OncePerRequestFilter {

    /** 放行后 request attr 注入的 strategyId(下游 Controller 取用)。 */
    public static final String WORKER_STRATEGY_ID_ATTR = "workerStrategyId";

    /** 放行后 request attr 注入的 userId(§3.7 R4 account 推导)。 */
    public static final String WORKER_USER_ID_ATTR = "workerUserId";

    /** 放行后 request attr 注入的 exchange(§3.7 R4 account 推导)。 */
    public static final String WORKER_EXCHANGE_ATTR = "workerExchange";

    public static final String TOKEN_HEADER = "X-Worker-Token";

    private final WorkerTokenService tokenService;

    public WorkerTokenFilter(WorkerTokenService tokenService) {
        this.tokenService = tokenService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {
        String path = req.getRequestURI();
        if (!isWorkerEndpoint(path)) {
            chain.doFilter(req, resp);
            return;
        }
        String token = req.getHeader(TOKEN_HEADER);
        WorkerTokenEntry entry = tokenService.getEntry(token);
        if (entry == null || !taskTypeMatchesEndpoint(entry.taskType(), path)) {
            resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            resp.setContentType("application/json");
            resp.getWriter()
                    .write("{\"code\":7301,\"message\":\"worker token invalid or endpoint mismatch\"}");
            return;
        }
        req.setAttribute(WORKER_STRATEGY_ID_ATTR, entry.strategyId());
        req.setAttribute(WORKER_USER_ID_ATTR, entry.userId());
        req.setAttribute(WORKER_EXCHANGE_ATTR, entry.exchange());
        chain.doFilter(req, resp);
    }

    /** Worker 端点:回测下单 /api/v1/backtests/{taskId}/orders 或 实盘/模拟下单 /api/v1/orders。 */
    private boolean isWorkerEndpoint(String path) {
        if (path == null) return false;
        return (path.startsWith("/api/v1/backtests/") && path.endsWith("/orders"))
                || path.equals("/api/v1/orders");
    }

    /** taskType 端点校验(R1):BACKTEST token 只能打回测端点,RUNNER 只能打 /api/v1/orders。 */
    private boolean taskTypeMatchesEndpoint(String taskType, String path) {
        boolean isBacktestEndpoint = path.startsWith("/api/v1/backtests/");
        if ("BACKTEST".equals(taskType)) return isBacktestEndpoint;
        if ("RUNNER".equals(taskType)) return !isBacktestEndpoint;
        return false;
    }
}
