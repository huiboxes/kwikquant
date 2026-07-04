package com.kwikquant.report.interfaces;

import com.kwikquant.report.application.PortfolioService;
import com.kwikquant.shared.infra.ApiResponse;
import com.kwikquant.shared.infra.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
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
            description = "聚合当前用户多账户余额，按 USDT 估值返回。需 JWT 鉴权。"
                    + "部分账户余额拉取失败时返回成功账户子集（降级）；全部账户失败时返回 502（6001）。")
    ApiResponse<PortfolioService.PortfolioSummary> summary() {
        long userId = SecurityUtils.currentUserId();
        return ApiResponse.ok(portfolioService.getSummary(userId));
    }

    @GetMapping("/pnl")
    @Operation(
            summary = "持仓未实现盈亏",
            description = "聚合当前用户多账户持仓的未实现盈亏。需 JWT 鉴权。"
                    + "余额拉取降级语义同 /summary；全部账户失败时返回 502（6001）。")
    ApiResponse<PortfolioService.PortfolioPnl> pnl() {
        long userId = SecurityUtils.currentUserId();
        return ApiResponse.ok(portfolioService.getPnl(userId));
    }
}
