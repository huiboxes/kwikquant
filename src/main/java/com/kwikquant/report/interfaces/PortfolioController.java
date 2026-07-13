package com.kwikquant.report.interfaces;

import com.kwikquant.report.application.PortfolioService;
import com.kwikquant.shared.infra.ApiResponse;
import com.kwikquant.shared.infra.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/portfolio")
@Tag(name = "组合总览")
class PortfolioController {

    private final PortfolioService portfolioService;

    PortfolioController(PortfolioService portfolioService) {
        this.portfolioService = portfolioService;
    }

    @GetMapping("/summary")
    @Operation(
            summary = "组合总览",
            description = "聚合当前用户多账户余额，按 USDT 估值返回。需 JWT 鉴权。" + "部分账户余额拉取失败时返回成功账户子集（降级）；全部账户失败时返回 502（6001）。")
    ApiResponse<PortfolioService.PortfolioSummary> summary() {
        long userId = SecurityUtils.currentUserId();
        return ApiResponse.ok(portfolioService.getSummary(userId));
    }

    @GetMapping("/pnl")
    @Operation(
            summary = "持仓未实现盈亏",
            description = "聚合当前用户多账户持仓的未实现盈亏。需 JWT 鉴权。" + "余额拉取降级语义同 /summary；全部账户失败时返回 502（6001）。")
    ApiResponse<PortfolioService.PortfolioPnl> pnl() {
        long userId = SecurityUtils.currentUserId();
        return ApiResponse.ok(portfolioService.getPnl(userId));
    }

    @GetMapping("/equity-curve")
    @Operation(
            summary = "组合权益曲线",
            description = "返回指定天数内的组合权益时间序列。需 JWT 鉴权。" + "当前为降级版本，返回基于实时 PnL 快照的单点数据；后续版本将补充定时采集的完整时间序列。")
    ApiResponse<List<PortfolioService.EquitySnapshot>> equityCurve(
            @Parameter(description = "查询天数，默认 7", example = "7") @RequestParam(defaultValue = "7") int days) {
        long userId = SecurityUtils.currentUserId();
        return ApiResponse.ok(portfolioService.getEquityCurve(userId, days));
    }
}
