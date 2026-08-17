package com.kwikquant.shared.infra;

import com.kwikquant.shared.infra.WorkerTokenService.WorkerTokenEntry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Worker→Java REST 认证 filter。验证 {@code X-Worker-Token} header,对照 {@link WorkerTokenService}
 * 内存 registry,校验 taskType 与端点匹配(BACKTEST→{@code /api/v1/backtests/{taskId}/klines|/progress};
 * RUNNER→{@code /api/v1/orders} + /api/v1/positions + /api/v1/market/klines + /api/v1/worker/bootstrap
 * + /api/v1/market/subscribe|unsubscribe/kline),放行后注入 strategyId 到 request attr 供下游用。
 *
 * <p>撮合本地化(Wave 2.2/2.3)后 BACKTEST 通道收窄为数据(klines)+ 心跳(progress)两个端点;
 * 原 {@code /orders} 回测下单端点随虚拟账本删除。
 *
 * <p>归 shared::infra(SecurityConfig 在 account,trading filter 会让 account→trading 违反模块
 * 边界;与 {@code UserContextFilter} 一致,跨切安全 filter 归 shared)。由 account/SecurityConfig 装配到
 * SecurityFilterChain 的 JwtAuthenticationFilter 之前(对 Worker 端点放行后标记已认证)。
 *
 * <p>401 直接写 response(@RestControllerAdvice 捕不到 filter 异常,故不抛 WorkerTokenInvalidException,
 * 直接写 401+7301 JSON)。token 从 WTS.getEntry 取 strategyId(非 path 反查),避开跨模块调 BacktestTaskMapper。
 */
public class WorkerTokenFilter extends OncePerRequestFilter {

    /** 放行后 request attr 注入的 strategyId(下游 Controller 取用)。 */
    public static final String WORKER_STRATEGY_ID_ATTR = "workerStrategyId";

    /** 放行后 request attr 注入的 userId(供下游推导 account)。 */
    public static final String WORKER_USER_ID_ATTR = "workerUserId";

    /** 放行后 request attr 注入的 exchange(供下游推导 account)。 */
    public static final String WORKER_EXCHANGE_ATTR = "workerExchange";

    /** 放行后 request attr 注入的 accountId(start 验属 user 后绑 token;OrderController/PositionController 用,去 exchange 推导)。 */
    public static final String WORKER_ACCOUNT_ID_ATTR = "workerAccountId";

    public static final String TOKEN_HEADER = "X-Worker-Token";

    private final WorkerTokenService tokenService;

    public WorkerTokenFilter(WorkerTokenService tokenService) {
        this.tokenService = tokenService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {
        String path = req.getRequestURI();
        // 仅当请求携带 X-Worker-Token header 时才走 Worker 鉴权逻辑。
        // 无该 header 的请求（JWT 用户）直接放行给后续 filter chain（JwtAuthenticationFilter）。
        // 原逻辑 isWorkerEndpoint(path) 精确匹配 /api/v1/orders 会劫持所有 JWT 用户的下单/列表请求。
        String token = req.getHeader(TOKEN_HEADER);
        if (token == null || token.isBlank()) {
            chain.doFilter(req, resp);
            return;
        }
        if (!isWorkerEndpoint(path)) {
            // 携带了 Worker token 但端点不匹配 → 可能是误用，放行给 JWT filter 处理
            chain.doFilter(req, resp);
            return;
        }
        WorkerTokenEntry entry = tokenService.getEntry(token);
        if (entry == null || !tokenMatchesEndpoint(entry, path)) {
            JsonErrorWriter.write(
                    resp,
                    HttpServletResponse.SC_UNAUTHORIZED,
                    ErrorCode.WORKER_TOKEN_INVALID,
                    "worker token invalid or endpoint mismatch");
            return;
        }
        req.setAttribute(WORKER_STRATEGY_ID_ATTR, entry.strategyId());
        req.setAttribute(WORKER_USER_ID_ATTR, entry.userId());
        req.setAttribute(WORKER_EXCHANGE_ATTR, entry.exchange());
        req.setAttribute(WORKER_ACCOUNT_ID_ATTR, entry.accountId());
        // 注入 Spring Security Authentication,让下游 TradingService
        // 通过 SecurityUtils.currentUserId() 拿 workerUserId,避免 NPE。principal=userId(String),
        // 与 JwtAuthenticationFilter 一致。
        // try/finally 清理 SecurityContextHolder,防 Tomcat 线程池 ThreadLocal
        // 泄漏到下一个请求(与 JwtAuthenticationFilter 一致的模式)。
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(String.valueOf(entry.userId()), null, List.of()));
        try {
            chain.doFilter(req, resp);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    /** Worker 端点:回测拉 K 线 /api/v1/backtests/{taskId}/klines、回测进度上报
     * /api/v1/backtests/{taskId}/progress(撮合本地化后回测通道仅剩数据+心跳),或 实盘/模拟下单
     * /api/v1/orders,或 runner 预填历史 bar 用的 /api/v1/market/klines(只读行情,与 JWT 用户共用),
     * 或 runner 拉取启动配置 /api/v1/worker/bootstrap(③ 拉取式配置下发,替代 env TASK_CONFIG_JSON)。 */
    private boolean isWorkerEndpoint(String path) {
        if (path == null) return false;
        return (path.startsWith("/api/v1/backtests/") && (path.endsWith("/klines") || path.endsWith("/progress")))
                || path.equals("/api/v1/orders")
                || path.startsWith("/api/v1/orders/")
                || path.equals("/api/v1/positions")
                || path.equals("/api/v1/market/klines")
                || path.equals("/api/v1/worker/bootstrap")
                || (path.startsWith("/api/v1/market/")
                        && (path.endsWith("/subscribe/kline") || path.endsWith("/unsubscribe/kline")));
    }

    /** taskType 端点校验(R1):BACKTEST token 只能打回测 klines/progress,RUNNER 不能打回测端点。 */
    private boolean tokenMatchesEndpoint(WorkerTokenEntry entry, String path) {
        boolean isBacktestEndpoint = path.startsWith("/api/v1/backtests/");
        if (WorkerTokenService.TASK_TYPE_BACKTEST.equals(entry.taskType())) {
            Long pathTaskId = backtestTaskId(path);
            return isBacktestEndpoint
                    && (path.endsWith("/klines") || path.endsWith("/progress"))
                    && pathTaskId != null
                    && pathTaskId.equals(entry.taskId());
        }
        if (WorkerTokenService.TASK_TYPE_RUNNER.equals(entry.taskType())) return !isBacktestEndpoint;
        return false;
    }

    private Long backtestTaskId(String path) {
        String prefix = "/api/v1/backtests/";
        if (!path.startsWith(prefix)) return null;
        int end = path.indexOf('/', prefix.length());
        if (end < 0) return null;
        try {
            return Long.valueOf(path.substring(prefix.length(), end));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
