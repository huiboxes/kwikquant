package com.kwikquant.trading.interfaces;

import com.kwikquant.shared.infra.ApiResponse;
import com.kwikquant.shared.infra.SecurityUtils;
import com.kwikquant.trading.application.TradingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 模拟盘账户重置端点。放 trading 模块(trading→account 允许;orchestration 调 trading 的
 * OrderMapper/PositionMapper + account 的 BalanceService.reset)。account 模块不能依赖 trading,
 * 故 plan 说的 ExchangeAccountController(account 模块)会违规,改放此 controller。
 */
@RestController
@RequestMapping("/api/v1/accounts")
@Tag(name = "模拟盘账户")
class PaperAccountController {

    private final TradingService tradingService;

    PaperAccountController(TradingService tradingService) {
        this.tradingService = tradingService;
    }

    @PostMapping("/{id}/paper/reset")
    @Operation(
            summary = "重置模拟盘账户",
            description = "需 JWT 鉴权。仅 PAPER 账户:取消活跃订单 + 清持仓 + 余额回 10 万 USDT。" + "非 PAPER 账户返回 400(7001)。仅可操作本人账户。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "400",
            description = "非 PAPER 账户(7001 VALIDATION_FAILED)")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "越权访问他人账户(1002 FORBIDDEN)")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "账户不存在(4001 RESOURCE_NOT_FOUND)")
    public ApiResponse<ResetResult> reset(@Parameter(description = "账户 ID", example = "42") @PathVariable long id) {
        tradingService.resetPaperAccount(id, SecurityUtils.currentUserId());
        return ApiResponse.ok(new ResetResult(id, "reset"), traceId());
    }

    private static String traceId() {
        return MDC.get("traceId");
    }

    /** 重置结果。 */
    public record ResetResult(
            @io.swagger.v3.oas.annotations.media.Schema(description = "账户 ID", example = "42") long accountId,
            @io.swagger.v3.oas.annotations.media.Schema(description = "操作", example = "reset") String action) {}
}
