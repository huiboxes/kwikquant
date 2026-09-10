package com.kwikquant.trading.interfaces;

import com.kwikquant.account.application.ExchangeAccountService;
import com.kwikquant.shared.infra.ApiResponse;
import com.kwikquant.shared.infra.WorkerTokenFilter;
import com.kwikquant.trading.application.TradingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * runner 断线增量补拉端点（worker 专用通道）。
 *
 * <p>鉴权与账户收口照 /api/v1/accounts/worker/balance 模板：X-Worker-Token（RUNNER）经
 * {@link WorkerTokenFilter} 注入 (accountId, userId) attr，账户由 token 绑定推导（worker 不持有
 * accountId 参数，严禁请求侧传账户），归属经 getOwned 复核。BACKTEST token 被
 * tokenMatchesEndpoint 拒（非 backtests/ 前缀端点 RUNNER-only）。
 */
@RestController
@RequestMapping("/api/v1/worker")
@Tag(name = "Worker fills catchup")
class WorkerFillCatchupController {

    /** 缺省单页行数。 */
    static final int DEFAULT_LIMIT = 100;
    /** 单页行数上限（FillMapper.listLiquidationsByAccount 同款 200 上限先例）。 */
    static final int MAX_LIMIT = 200;

    private final TradingService tradingService;
    private final ExchangeAccountService accountService;

    WorkerFillCatchupController(TradingService tradingService, ExchangeAccountService accountService) {
        this.tradingService = tradingService;
        this.accountService = accountService;
    }

    @GetMapping("/fills-since")
    @Operation(
            summary = "成交增量补拉（Worker 通道）",
            description = "需 X-Worker-Token（RUNNER）鉴权，账户由 token 绑定推导。runner WS 断线期间"
                    + "丢失的 on_fill 事件经本端点增量补拉（worker 按 fillId 去重后派发回调）；"
                    + "afterId 缺省 = 播种模式：返回空行列表 + 当前安全尾部游标（进程重启不回放历史事件，"
                    + "重启窗口缺口仍归 ctx.position()/REST 对账契约）。仅返回安全边界（created_at 滞后 2s）内"
                    + "已提交的行；强平行不返回（强平走 LiquidationEvent 通道，不进 on_fill）。"
                    + "BACKTEST token 拒（401/7301）；JWT 用户请求 400（3001）。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "400",
            description = "非 worker token 请求（3001 VALIDATION_FAILED）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "401",
            description = "worker token 无效或种类不符（7301 WORKER_TOKEN_INVALID）")
    public ApiResponse<FillsSinceView> fillsSince(
            @Parameter(description = "游标：只返回 id 大于该值的行；缺省 = 播种模式（返回当前安全尾部游标）", example = "1024")
                    @RequestParam(required = false)
                    Long afterId,
            @Parameter(description = "单页行数上限（默认 100，范围 1–200，越界钳制）", example = "100") @RequestParam(required = false)
                    Integer limit,
            HttpServletRequest httpReq) {
        Long workerAccountId = (Long) httpReq.getAttribute(WorkerTokenFilter.WORKER_ACCOUNT_ID_ATTR);
        Long workerUserId = (Long) httpReq.getAttribute(WorkerTokenFilter.WORKER_USER_ID_ATTR);
        if (workerAccountId == null || workerUserId == null) {
            throw new IllegalArgumentException("fills-since requires a RUNNER X-Worker-Token");
        }
        // 归属复核（token 绑定 accountId 与账户 userId 必须一致，防越权——balance 端点同款）
        var account = accountService.getOwned(workerAccountId, workerUserId);
        int effectiveLimit = limit == null ? DEFAULT_LIMIT : Math.min(Math.max(limit, 1), MAX_LIMIT);
        return ApiResponse.ok(
                FillsSinceView.from(tradingService.listFillsSince(account.getId(), afterId, effectiveLimit)));
    }
}
