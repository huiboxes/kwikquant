package com.kwikquant.report.interfaces;

import com.kwikquant.report.application.PortfolioService;
import com.kwikquant.shared.infra.ApiResponse;
import com.kwikquant.shared.infra.SecurityUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/portfolio")
class PortfolioController {

    private final PortfolioService portfolioService;

    PortfolioController(PortfolioService portfolioService) {
        this.portfolioService = portfolioService;
    }

    @GetMapping("/summary")
    ApiResponse<PortfolioService.PortfolioSummary> summary() {
        long userId = SecurityUtils.currentUserId();
        return ApiResponse.ok(portfolioService.getSummary(userId));
    }

    @GetMapping("/pnl")
    ApiResponse<PortfolioService.PortfolioPnl> pnl() {
        long userId = SecurityUtils.currentUserId();
        return ApiResponse.ok(portfolioService.getPnl(userId));
    }
}
